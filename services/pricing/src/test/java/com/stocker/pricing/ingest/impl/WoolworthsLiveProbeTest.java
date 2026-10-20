package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * ONE live page load of ONE Woolworths NZ category page, to see what the product tiles really look like. Never runs by
 * default: needs STOCKER_PROBE_WOOLWORTHS_LIVE=true and the operator's approval for the request. No retry. Writes only
 * small excerpts to build/woolworths-probe/ (no full HTML, no cookies, no headers).
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_PROBE_WOOLWORTHS_LIVE", matches = "true")
class WoolworthsLiveProbeTest {

	private static final String URL = "https://www.woolworths.co.nz/shop/browse/pantry/biscuits-crackers/biscuits-cookies";
	private static final Path OUT = Path.of("build/woolworths-probe");

	private static final String CANDIDATES_JS = """
			() => {
			  const sels = ["div[class^='product-tile_imgAndTextArea_']", "[class*='product-tile_imgAndTextArea_']",
			    "[class*='product-tile']", "product-tile", "cdx-card", "[class*='product-entry']", "[data-testid*='product']",
			    "a[href*='/shop/productdetails/']", "a[class^='product-tile_description_']", "[class*='product-tile_description']",
			    "span[class^='product-price_value_']", "[class*='product-price']", "h3", "article", "li"];
			  const counts = {};
			  for (const s of sels) { try { counts[s] = document.querySelectorAll(s).length; } catch (e) { counts[s] = -1; } }
			  // smallest ancestor of a productdetails link that also contains a '$'
			  const tiles = [];
			  const seen = new Set();
			  for (const a of document.querySelectorAll("a[href*='/shop/productdetails/']")) {
			    let el = a;
			    while (el && el.parentElement && !(el.textContent || '').includes('$')) el = el.parentElement;
			    if (el && !seen.has(el) && el !== document.body) { seen.add(el); tiles.push(el); }
			    if (tiles.length >= 3) break;
			  }
			  const cap = (h) => h.length > 6000 ? h.substring(0, 6000) + '...[cut]' : h;
			  const mine = Array.from(document.querySelectorAll("div[class^='product-tile_imgAndTextArea_']")).slice(0, 3)
			    .map(e => cap(e.outerHTML));
			  return { counts, mine, real: tiles.map(e => cap(e.outerHTML)) };
			}
			""";

	@Test
	void probeOnePage() throws Exception {
		Files.createDirectories(OUT);
		Map<String, Object> meta = new LinkedHashMap<>();
		try (Playwright pw = Playwright.create();
				Browser browser = pw.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
				BrowserContext context = browser.newContext()) {
			Page page = context.newPage();
			page.setDefaultTimeout(30000);
			Response response = page.navigate(URL);
			meta.put("httpStatus", response == null ? null : response.status());
			try {
				page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE,
						new Page.WaitForLoadStateOptions().setTimeout(15000));
			} catch (RuntimeException e) {
				meta.put("networkIdle", "timeout");
			}
			try {
				page.waitForFunction("() => document.querySelectorAll(\"a[href*='/shop/productdetails/']\").length > 0",
						null, new Page.WaitForFunctionOptions().setTimeout(15000));
			} catch (RuntimeException e) {
				meta.put("productLinkWait", "timeout");
			}
			for (int i = 0; i < 3; i++) {
				page.mouse().wheel(0, 1500);
				page.waitForTimeout(800);
			}
			meta.put("finalUrl", page.url());
			meta.put("title", page.title());
			String body = page.locator("body").innerText();
			meta.put("bodyExcerpt", body.length() > 2048 ? body.substring(0, 2048) : body);
			String lower = (page.title() + " " + body).toLowerCase();
			meta.put("looksBlocked", lower.contains("captcha") || lower.contains("access denied")
					|| lower.contains("verify you are") || lower.contains("are you a robot")
					|| lower.contains("just a moment"));
			@SuppressWarnings("unchecked")
			Map<String, Object> result = (Map<String, Object>) page.evaluate(CANDIDATES_JS);
			meta.put("selectorCounts", result.get("counts"));
			write("our-selector-cards.html", String.join("\n\n=====\n\n", (List<String>) result.get("mine")));
			write("real-tiles.html", String.join("\n\n=====\n\n", (List<String>) result.get("real")));
		}
		StringBuilder sb = new StringBuilder();
		meta.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n\n"));
		write("summary.txt", sb.toString());
	}

	private static void write(String name, String content) throws Exception {
		Files.writeString(OUT.resolve(name), content, StandardCharsets.UTF_8);
	}
}
