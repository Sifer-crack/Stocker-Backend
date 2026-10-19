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

	/**
	 * The product grid renders client-side after the "load" event, so reading immediately can see zero tiles on a valid
	 * page. Wait briefly for the first tile; a timeout is a legitimate "zero products" outcome, not an error.
	 */
	private void waitForProductCards(Page page) {
		try {
			page.waitForSelector(template.cardSelector(), new Page.WaitForSelectorOptions().setTimeout(5000));
		} catch (PlaywrightException e) {
			// No tiles appeared in time - the caller's empty-list handling takes over.
		}
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
		for (ChainPageTemplate.MandatoryField field : template.mandatory()) {
			boolean seen = cards.stream().anyMatch(c -> {
				String v = field.value().apply(c);
				return v != null && !v.isBlank();
			});
			if (!seen) {
				log.warn("{}: page template field '{}' ({}) found nothing on any of {} product tiles at {} - "
						+ "the site layout probably changed; adjust ChainPageTemplate", chain, field.name(),
						field.selectorDescription(), cards.size(), url);
			}
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
