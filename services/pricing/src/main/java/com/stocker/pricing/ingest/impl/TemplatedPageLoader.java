package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.CrawlBrake;
import com.stocker.pricing.ingest.parse.CategoryPagination;
import com.stocker.pricing.ingest.parse.ChainPageTemplate;
import com.stocker.pricing.ingest.parse.ProductCardFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads one category page and reads it through the chain's {@link ChainPageTemplate} in a single {@code page.evaluate}.
 * Shared by the New World / PAK'nSAVE and the Woolworths scrapers: navigate, circuit-breaker status handling, wait for
 * the grid, one evaluate, template self-check, one summary log line.
 */
final class TemplatedPageLoader {

	private static final Logger log = LoggerFactory.getLogger(TemplatedPageLoader.class);

	/** Result of reading a page: the tiles and the "Showing A - B of M products" line (null when absent / not applicable). */
	record PageRead(List<ProductCardFields> cards, String showingText) {
	}

	/**
	 * The one script run in the page. Returns {cards: [{field: string|null}], showing: string|null, nav: boolean};
	 * the visible-text regex runs only when tiles exist (a challenge page is inspected separately by BlockGuard).
	 */
	static final String EVALUATE_SCRIPT = """
			(spec) => {
			  const cards = Array.from(document.querySelectorAll(spec.card)).map(card => {
			    const out = {};
			    for (const [name, f] of Object.entries(spec.fields)) {
			      const el = f.selector === '' ? card : card.querySelector(f.selector);
			      out[name] = el ? (f.attribute ? el.getAttribute(f.attribute) : el.textContent) : null;
			    }
			    return out;
			  });
			  let showing = null;
			  if (spec.showing && cards.length > 0) {
			    const m = new RegExp(spec.showing, 'i').exec(document.body ? document.body.innerText : '');
			    showing = m ? m[0] : null;
			  }
			  const nav = spec.nav ? document.querySelector(spec.nav) !== null : false;
			  return { cards, showing, nav };
			}
			""";

	private final ChainId chain;
	private final ChainPageTemplate template;
	private final LeanPageRouter router;
	private final PageCountMonitor monitor;
	private final boolean logRequests;

	TemplatedPageLoader(ChainId chain, ChainPageTemplate template, LeanPageRouter router, PageCountMonitor monitor,
			boolean logRequests) {
		this.chain = chain;
		this.template = template;
		this.router = router;
		this.monitor = monitor;
		this.logRequests = logRequests;
	}

	PageRead load(Page page, String url, CrawlBrake brake) {
		LeanPageRouter.PageStats stats = router.beginPage();
		Response response = page.navigate(url);
		BlockGuard.failIfBlocked(response, url, brake);
		waitForProductCards(page);
		waitForShowingLine(page);
		Map<String, Object> raw = evaluate(page);
		List<ProductCardFields> cards = toCards(raw);
		if (cards.isEmpty()) {
			BlockGuard.failIfChallenge(page, url, brake);
		}
		brake.onSuccess();
		return report(url, raw, cards, stats);
	}

	/** Reads the page that is already loaded (saved fixtures set into a blank page); same template, checks and log line. */
	PageRead readCurrent(Page page, String url) {
		Map<String, Object> raw = evaluate(page);
		return report(url, raw, toCards(raw), router.currentStats());
	}

	private PageRead report(String url, Map<String, Object> raw, List<ProductCardFields> cards,
			LeanPageRouter.PageStats stats) {
		String showing = raw.get("showing") instanceof String s ? s : null;
		boolean navPresent = Boolean.TRUE.equals(raw.get("nav"));
		check(url, cards, showing, navPresent);
		summarise(url, cards.size(), showing, stats);
		return new PageRead(cards, showing);
	}

	String cardSelector() {
		return template.cardSelector();
	}

	/** True when the page has tiles and (for {@code spec.name} != null) every tile already shows its name. */
	static final String TILES_READY_SCRIPT = """
			(spec) => {
			  const cards = Array.from(document.querySelectorAll(spec.card));
			  if (cards.length === 0) return false;
			  if (!spec.name) return true;
			  const named = cards.filter(c => { const el = c.querySelector(spec.name); return el && el.textContent.trim() !== ''; });
			  return spec.all ? named.length === cards.length : named.length > 0;
			}
			""";

	private static final int HYDRATION_WAIT_MILLIS = 15000;
	private static final int SCROLL_STEPS = 3;

	/**
	 * The product grid renders client-side after the "load" event, so reading immediately can see zero tiles on a valid
	 * page. Wait briefly for the first tile; a timeout is a legitimate "zero products" outcome, not an error. When the
	 * template says tile wrappers exist before their content (Woolworths), also wait until tiles show a name, then
	 * scroll (at most 3 times, once) to hydrate lazily rendered rows.
	 */
	private void waitForProductCards(Page page) {
		try {
			page.waitForSelector(template.cardSelector(), new Page.WaitForSelectorOptions().setTimeout(5000));
		} catch (PlaywrightException e) {
			return;      // No tiles appeared in time - the caller's empty-list handling takes over.
		}
		if (!template.waitForNamedTiles()) {
			return;
		}
		try {
			waitForNamedTiles(page);
		} catch (RuntimeException e) {
			log.debug("{}: hydration wait ended early: {}", chain, e.toString());
		}
	}

	/** True when the visible text already holds the chain's "Showing A - B of M" line. */
	static final String SHOWING_READY_SCRIPT = """
			(source) => new RegExp(source, 'i').test(document.body ? document.body.innerText : '')
			""";

	private static final int SHOWING_WAIT_MILLIS = 5000;

	/**
	 * The count line can render after the tiles, so reading straight away may miss it and end the category walk early.
	 * Wait for it (bounded); a timeout just means the page has no such line and the caller carries on.
	 */
	private void waitForShowingLine(Page page) {
		if (template.showingRegex() == null) {
			return;
		}
		try {
			if (page.querySelector(template.cardSelector()) == null) {
				return;      // no tiles: nothing to count (a zero-result page is handled by the caller)
			}
			page.waitForFunction(SHOWING_READY_SCRIPT, template.showingRegex(),
					new Page.WaitForFunctionOptions().setTimeout(SHOWING_WAIT_MILLIS));
		} catch (PlaywrightException e) {
			log.debug("{}: no count line within {} ms", chain, SHOWING_WAIT_MILLIS);
		}
	}

	private void waitForNamedTiles(Page page) {
		String nameSelector = template.fields().get(ChainPageTemplate.NAME).selector();
		try {
			page.waitForFunction(TILES_READY_SCRIPT, readySpec(nameSelector, false),
					new Page.WaitForFunctionOptions().setTimeout(HYDRATION_WAIT_MILLIS));
		} catch (PlaywrightException e) {
			log.debug("{}: no tile showed a name within {} ms", chain, HYDRATION_WAIT_MILLIS);
			return;
		}
		for (int i = 0; i < SCROLL_STEPS; i++) {
			try {
				if (Boolean.TRUE.equals(page.evaluate(TILES_READY_SCRIPT, readySpec(nameSelector, true)))) {
					return;
				}
				page.mouse().wheel(0, 1500);
				page.waitForFunction(TILES_READY_SCRIPT, readySpec(nameSelector, true),
						new Page.WaitForFunctionOptions().setTimeout(2500));
				return;
			} catch (PlaywrightException e) {
				// still some unnamed tiles after this scroll: scroll again, then read what there is
			}
		}
	}

	private Map<String, Object> readySpec(String nameSelector, boolean all) {
		Map<String, Object> spec = new HashMap<>();
		spec.put("card", template.cardSelector());
		spec.put("name", nameSelector);
		spec.put("all", all);
		return spec;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> evaluate(Page page) {
		Object result = page.evaluate(EVALUATE_SCRIPT, template.toEvaluateArgument());
		return result instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	@SuppressWarnings("unchecked")
	private List<ProductCardFields> toCards(Map<String, Object> raw) {
		List<ProductCardFields> cards = new ArrayList<>();
		if (!(raw.get("cards") instanceof List<?> list)) {
			return cards;
		}
		for (Object item : list) {
			Map<String, String> tile = new HashMap<>();
			((Map<String, Object>) item).forEach((k, v) -> tile.put(k, v == null ? null : v.toString()));
			cards.add(new ProductCardFields(tile.get(ChainPageTemplate.NAME), tile.get(ChainPageTemplate.BRAND),
					template.priceText(tile), template.nativeCode(tile.get(ChainPageTemplate.CODE)),
					tile.get(ChainPageTemplate.IMAGE), null));
		}
		return cards;
	}

	/** A mandatory value that NO tile of the page yields means the site layout changed: say so, by name. */
	private void check(String url, List<ProductCardFields> cards, String showing, boolean navPresent) {
		if (cards.isEmpty()) {
			return;      // the scraper reports the zero-result page itself
		}
		List<ChainPageTemplate.MandatoryField> missing = new ArrayList<>();
		for (ChainPageTemplate.MandatoryField field : template.mandatory()) {
			boolean seen = cards.stream().anyMatch(c -> {
				String v = field.value().apply(c);
				return v != null && !v.isBlank();
			});
			if (!seen) {
				missing.add(field);
			}
		}
		if (!missing.isEmpty()) {
			// ONE line per page, however many fields are missing.
			ChainPageTemplate.MandatoryField first = missing.get(0);
			StringBuilder also = new StringBuilder();
			for (ChainPageTemplate.MandatoryField other : missing.subList(1, missing.size())) {
				also.append(also.length() == 0 ? "; also missing: " : ", ").append('\'').append(other.name()).append("' (")
						.append(other.selectorDescription()).append(')');
			}
			log.warn("{}: page template field '{}' ({}) found nothing on any of {} product tiles at {} - "
					+ "the site layout probably changed; adjust ChainPageTemplate{}", chain, first.name(),
					first.selectorDescription(), cards.size(), url, also);
		}
		if (template.showingRegex() != null && showing == null) {
			log.warn("{}: page template field 'showing' (\"Showing A - B of M products\") not found at {} "
					+ "(pagination nav {} present) - later pages of this category will not be walked", chain, url,
					navPresent ? "IS" : "is NOT");
		}
	}

	private void summarise(String url, int tiles, String showing, LeanPageRouter.PageStats stats) {
		String claimed = showing == null ? "?" : CategoryPagination.parse(showing).map(p -> String.valueOf(p.total()))
				.orElse("?");
		log.info("{} page url={} tiles={} claimed={} blocked-requests={}/{}", chain, url, tiles, claimed,
				stats.blocked(), stats.total());
		if (logRequests) {
			log.info("{} requests url={}: {}", chain, url, stats.describe());
		}
		monitor.record(url, tiles).ifPresent(warning -> log.warn("{}: {}", chain, warning));
	}
}
