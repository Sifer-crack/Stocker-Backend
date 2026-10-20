package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ScrapePacer;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.CrawlBrake;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import com.stocker.pricing.ingest.parse.ChainPageTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * EXACTLY TWO live page loads of Woolworths NZ (operator-approved, no retry): page 1 of one category, then the page-2
 * link found on it. Saves the pagination markup and a few facts to build/woolworths-pagination-probe/ (no full HTML, no
 * cookies, no headers). Stops at once on any failure (403 / 429 / captcha / timeout) and writes what it has.
 * Needs STOCKER_PROBE_WOOLWORTHS_PAGINATION_LIVE=true.
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_PROBE_WOOLWORTHS_PAGINATION_LIVE", matches = "true")
class WoolworthsPaginationLiveProbeTest {

	private static final String URL = "https://www.woolworths.co.nz/shop/browse/pantry/biscuits-crackers/biscuits-cookies";
	private static final Path OUT = Path.of("build/woolworths-pagination-probe");

	private static final String PAGINATION_JS = """
			() => {
			  const cap = (h) => h.length > 8000 ? h.substring(0, 8000) + '...[cut]' : h;
			  const countRe = /^\\s*\\d+\\s+to\\s+\\d+\\s+of\\s+\\d+\\s+products?\\s*$/i;
			  const out = { navs: [], count: [], next: [], pageLinks: [], relNext: [] };
			  for (const el of document.querySelectorAll('[aria-label]')) {
			    const l = el.getAttribute('aria-label') || '';
			    if (/pagination|page/i.test(l) && out.navs.length < 5) out.navs.push(cap(el.outerHTML));
			  }
			  let best = null;
			  for (const el of document.querySelectorAll('body *')) {
			    if (countRe.test(el.textContent || '') && el.children.length <= 2) best = el;
			  }
			  if (best) {
			    out.count.push(cap(best.outerHTML));
			    if (best.parentElement) out.count.push(cap(best.parentElement.outerHTML));
			    out.countText = best.textContent.trim();
			  }
			  for (const el of document.querySelectorAll('a, button')) {
			    const t = ((el.textContent || '') + ' ' + (el.getAttribute('aria-label') || '')).trim();
			    if (/\\bnext\\b/i.test(t) && out.next.length < 5) out.next.push(cap(el.outerHTML));
			  }
			  for (const a of document.querySelectorAll('a[href]')) {
			    const h = a.getAttribute('href');
			    if (/[?&](page|pg|p)=\\d+/i.test(h) && out.pageLinks.length < 12) out.pageLinks.push(h);
			  }
			  for (const l of document.querySelectorAll('link[rel~="next"]')) out.relNext.push(l.outerHTML);
			  return out;
			}
			""";

	private static final String NEXT_HREF_JS = """
			() => {
			  const cands = Array.from(document.querySelectorAll('a[href]'));
			  const byText = cands.find(a => /\\bnext\\b/i.test((a.textContent || '') + ' ' + (a.getAttribute('aria-label') || '')));
			  if (byText) return byText.href;
			  const byParam = cands.find(a => /[?&](page|pg|p)=2(\\D|$)/i.test(a.getAttribute('href')));
			  return byParam ? byParam.href : null;
			}
			""";

	private static final String FIRST_NAME_JS = """
			() => { const e = document.querySelector("a[class^='product-tile_description_']"); return e ? e.textContent.trim() : null; }
			""";

	private static final String COUNT_JS = """
			() => { const m = /\\d+\\s+to\\s+\\d+\\s+of\\s+\\d+\\s+products?/i.exec(document.body ? document.body.innerText : ''); return m ? m[0] : null; }
			""";

	@Test
	void twoPageLoads() throws Exception {
		Files.createDirectories(OUT);
		Map<String, Object> meta = new LinkedHashMap<>();
		ChainPageTemplate template = ChainPageTemplate.woolworths();
		LeanPageRouter router = new LeanPageRouter(ChainId.WOOLWORTHS, LeanPageOptions.OFF, "https://www.woolworths.co.nz");
		TemplatedPageLoader loader = new TemplatedPageLoader(ChainId.WOOLWORTHS, template, router, new PageCountMonitor(), false);
		ChainScraperConfig pace = ChainScraperConfig.builder().pageDelayMinMillis(20000).pageDelayMaxMillis(40000).build();
		try (Playwright pw = Playwright.create();
				Browser browser = pw.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = browser.newContext()) {
			router.install(context);
			Page page = context.newPage();
			page.setDefaultTimeout(30000);
			try {
				// LOAD 1
				TemplatedPageLoader.PageRead first = loader.load(page, URL, CrawlBrake.NONE);
				meta.put("page1.finalUrl", page.url());
				meta.put("page1.cardCount", first.cards().size());
				meta.put("page1.firstCardName", page.evaluate(FIRST_NAME_JS));
				meta.put("page1.countText", page.evaluate(COUNT_JS));
				@SuppressWarnings("unchecked")
				Map<String, Object> pag = (Map<String, Object>) page.evaluate(PAGINATION_JS);
				write("pagination-markup.txt", asText(pag));
				meta.put("page1.nextLinkCount", pag.get("next") instanceof List<?> l ? l.size() : 0);
				meta.put("page1.pageLinkHrefs", pag.get("pageLinks"));
				meta.put("page1.relNext", pag.get("relNext"));
				Object href = page.evaluate(NEXT_HREF_JS);
				meta.put("page1.nextHref", href);
				if (href == null) {
					meta.put("STOP", "no page-2 link found on page 1; second load NOT made");
				} else {
					ScrapePacer.pauseBetweenPages(pace);
					// LOAD 2
					TemplatedPageLoader.PageRead second = loader.load(page, href.toString(), CrawlBrake.NONE);
					meta.put("page2.finalUrl", page.url());
					meta.put("page2.countText", page.evaluate(COUNT_JS));
					meta.put("page2.cardCount", second.cards().size());
					Object name2 = page.evaluate(FIRST_NAME_JS);
					meta.put("page2.firstCardName", name2);
					meta.put("page2.firstDiffersFromPage1", !String.valueOf(name2).equals(String.valueOf(meta.get("page1.firstCardName"))));
				}
			} catch (RuntimeException e) {
				meta.put("STOP", "failure, no retry: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
			}
		}
		StringBuilder sb = new StringBuilder();
		meta.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
		write("summary.txt", sb.toString());
	}

	private static String asText(Map<String, Object> m) {
		StringBuilder sb = new StringBuilder();
		m.forEach((k, v) -> {
			sb.append("##### ").append(k).append('\n');
			if (v instanceof List<?> list) {
				list.forEach(x -> sb.append(x).append("\n-----\n"));
			} else {
				sb.append(v).append('\n');
			}
		});
		return sb.toString();
	}

	private static void write(String name, String content) throws Exception {
		Files.writeString(OUT.resolve(name), content, StandardCharsets.UTF_8);
	}
}
