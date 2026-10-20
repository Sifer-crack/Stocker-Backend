package com.stocker.pricing.integration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.stocker.pricing.ingest.ScrapePacer;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * One-off live probe (approved once by the owner, 2026-10-20): walks ALL pages of ONE large category per chain, at
 * most 10 pages each, to record what the real pagination markup looks like. Firefox only, paced between page loads,
 * no Spring, no database, no retry: the first 403 / 429 / 503, captcha page, timeout or error stops the whole probe.
 * Report: build/pagination-probe.txt; per-page text + pagination markup: build/pagination-probe-fixtures/.
 * Disabled unless STOCKER_PROBE_PAGINATION_LIVE=true.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_PROBE_PAGINATION_LIVE", matches = "true")
class PaginationLiveProbeTest {

	private static final int HARD_CAP_PAGES = 10;
	private static final Pattern SHOWING = Pattern.compile("(?i)showing\\s+(\\d+)\\s*[-\\u2013]\\s*(\\d+)\\s+of\\s+(\\d+)\\s+products?");
	private static final Pattern COUNT_TEXT = Pattern.compile(
			"(?i)([^\\n]{0,40}\\b\\d{1,5}\\s*(?:products?|results?|items?)\\b[^\\n]{0,40})");

	private record Target(String chain, String firstUrl, String cardSelector, String pageParam) {
	}

	private static final List<Target> TARGETS = List.of(
			new Target("NEWWORLD", "https://www.newworld.co.nz/shop/category/pantry/chips-nuts--snacks?pg=1",
					"div[itemtype='https://schema.org/Product']", "pg"),
			new Target("PAKNSAVE", "https://www.paknsave.co.nz/shop/category/pantry/chips-nuts--snacks?pg=1",
					"div[itemtype='https://schema.org/Product']", "pg"),
			new Target("WOOLWORTHS", "https://www.woolworths.co.nz/shop/browse/pantry/biscuits-crackers/biscuits-cookies",
					"div[class^='product-tile_imgAndTextArea_']", "page"));

	@Test
	void probe() throws Exception {
		StringBuilder report = new StringBuilder("Pagination live probe (<=" + HARD_CAP_PAGES
				+ " pages per chain, paced, no retry, stops on first block/error)\n");
		ChainScraperConfig pacing = ChainScraperConfig.builder().pageDelayMinMillis(8000).pageDelayMaxMillis(15000).build();
		Path fixtures = Path.of("build", "pagination-probe-fixtures");
		Files.createDirectories(fixtures);
		boolean stop = false;

		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
			boolean first = true;
			for (Target t : TARGETS) {
				if (stop) {
					report.append("\n== ").append(t.chain).append(": SKIPPED (an earlier chain was blocked / errored)\n");
					continue;
				}
				if (!first) {
					ScrapePacer.pauseBetweenPages(pacing);
				}
				first = false;
				stop = probeChain(browser, t, pacing, report, fixtures);
			}
		} finally {
			Path out = Path.of("build", "pagination-probe.txt");
			Files.createDirectories(out.getParent());
			Files.writeString(out, report.toString());
		}
	}

	/** @return true when a block or error happened (nothing further may be loaded). */
	private boolean probeChain(Browser browser, Target t, ChainScraperConfig pacing, StringBuilder r, Path fixtures)
			throws Exception {
		r.append("\n== ").append(t.chain).append("\nstart URL: ").append(t.firstUrl).append('\n');
		Set<String> seenCodes = new LinkedHashSet<>();
		try (BrowserContext context = browser.newContext()) {
			Page page = context.newPage();
			page.setDefaultTimeout(30_000);
			String url = t.firstUrl;
			for (int n = 1; n <= HARD_CAP_PAGES; n++) {
				if (n > 1) {
					ScrapePacer.pauseBetweenPages(pacing);
				}
				long t0 = System.currentTimeMillis();
				Response resp = page.navigate(url);
				int status = resp == null ? -1 : resp.status();
				try {
					page.waitForSelector(t.cardSelector, new Page.WaitForSelectorOptions().setTimeout(6000));
				} catch (RuntimeException ignored) {
					// zero tiles is reported below
				}
				String title = page.title();
				String lower = title.toLowerCase();
				r.append("\n-- page ").append(n).append(": ").append(url).append("\nHTTP ").append(status).append(", title: ")
						.append(title).append(", ").append(System.currentTimeMillis() - t0).append(" ms\n");
				String body = page.locator("body").innerText();
				if (status == 403 || status == 429 || status == 503 || lower.contains("just a moment")
						|| lower.contains("attention required") || lower.contains("captcha")
						|| body.toLowerCase().contains("verify you are human")) {
					r.append("RESULT: BLOCKED / challenge detected - probe stopped, no retry.\n");
					return true;
				}
				int tiles = page.locator(t.cardSelector).count();
				int fresh = 0;
				for (String id : tileIds(page, t.cardSelector)) {
					if (seenCodes.add(id)) {
						fresh++;
					}
				}
				r.append("tiles: ").append(tiles).append(" (new vs earlier pages: ").append(fresh).append(")\n");
				Matcher m = SHOWING.matcher(body);
				String showing = m.find() ? m.group() : null;
				r.append("'Showing A - B of M': ").append(showing == null ? "not found" : showing).append('\n');
				List<String> counts = new ArrayList<>();
				Matcher c = COUNT_TEXT.matcher(body);
				while (c.find() && counts.size() < 4) {
					counts.add(c.group(1).strip());
				}
				r.append("count-like text: ").append(counts).append('\n');
				Object nav = page.evaluate("() => Array.from(document.querySelectorAll("
						+ "'[data-testid*=\"pagination\"], [class*=\"pagination\"], [class*=\"Pagination\"], nav[aria-label*=\"agination\"]'))"
						+ ".map(e => e.outerHTML.replace(/\\s+/g,' ').slice(0, 2500))");
				r.append("pagination markup: ").append(nav).append('\n');
				Object links = page.evaluate("() => Array.from(document.querySelectorAll('a[href*=\"" + t.pageParam + "=\"]'))"
						+ ".map(e => (e.getAttribute('href')||'') + ' | ' + (e.innerText||'').trim().slice(0,20)).slice(0,12)");
				r.append("links carrying ").append(t.pageParam).append("=: ").append(links).append('\n');
				Files.writeString(fixtures.resolve(t.chain.toLowerCase() + "-page" + n + ".txt"),
						"URL: " + url + "\nTILES: " + tiles + "\nSHOWING: " + showing + "\n\nPAGINATION MARKUP: " + nav
								+ "\n\nBODY TEXT (first 6000 chars):\n" + body.substring(0, Math.min(6000, body.length())));

				if (tiles == 0) {
					r.append("RESULT: zero tiles - stopping this chain.\n");
					return false;
				}
				boolean last = false;
				if (m.find(0)) {
					last = Integer.parseInt(m.group(2)) >= Integer.parseInt(m.group(3));
				} else if (!hasNext(links, n + 1, t.pageParam)) {
					last = true;
					r.append("no 'Showing' line and no link to page ").append(n + 1).append(" - taking this as the last page.\n");
				}
				if (last) {
					r.append("RESULT: reached the last page (").append(n).append(" page(s)), ").append(seenCodes.size())
							.append(" distinct tiles in total.\n");
					return false;
				}
				if (fresh == 0 && n > 1) {
					r.append("RESULT: page ").append(n).append(" repeated earlier tiles - the pg parameter is not paging; stopping.\n");
					return false;
				}
				url = nextUrl(url, n + 1, t.pageParam);
			}
			r.append("RESULT: hard cap of ").append(HARD_CAP_PAGES).append(" pages reached; ").append(seenCodes.size())
					.append(" distinct tiles so far.\n");
			return false;
		} catch (RuntimeException e) {
			r.append("RESULT: page load/probe ERROR (no retry): ").append(e.getClass().getSimpleName()).append(": ")
					.append(String.valueOf(e.getMessage()).lines().findFirst().orElse("")).append('\n');
			return true;
		}
	}

	private static boolean hasNext(Object links, int next, String param) {
		return String.valueOf(links).contains(param + "=" + next);
	}

	private static String nextUrl(String url, int page, String param) {
		Matcher m = Pattern.compile("([?&])" + param + "=\\d+").matcher(url);
		if (m.find()) {
			return m.replaceFirst(m.group(1) + param + "=" + page);
		}
		return url + (url.contains("?") ? "&" : "?") + param + "=" + page;
	}

	@SuppressWarnings("unchecked")
	private static List<String> tileIds(Page page, String selector) {
		return (List<String>) page.evaluate("() => Array.from(document.querySelectorAll(\"" + selector
				+ "\")).map(e => e.getAttribute('data-testid') || e.querySelector('a[class^=\"product-tile_productImageLink_\"]')?.getAttribute('href') || e.innerText.slice(0,60))");
	}
}
