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
 * One-off live probe: loads the eggs category page ONCE per chain (New World, PAK'nSave), no retries,
 * no pagination navigation, at most 3 scrolls on the already-loaded page. Writes no database; report goes to
 * build/eggs-probe.txt. Disabled unless STOCKER_PROBE_EGGS_LIVE=true.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_PROBE_EGGS_LIVE", matches = "true")
class EggsCategoryLiveProbeTest {

	// Same selector/code pattern as ingest/impl/FoodstuffsChainScraper (private there).
	private static final String CARD = "div[itemtype='https://schema.org/Product']";
	private static final Pattern NATIVE_CODE = Pattern.compile("product-(\\d+)-\\w+-\\d+");
	private static final Pattern TOTAL_TEXT = Pattern.compile(
			"(?i)([^\\n]{0,40}\\b\\d{1,4}\\s*(?:products?|results?|items?)\\b[^\\n]{0,40})");

	@Test
	void probe() throws Exception {
		String[][] targets = {
				{"NEWWORLD", "https://www.newworld.co.nz/shop/category/fridge-deli-and-eggs/eggs?pg=1"},
				{"PAKNSAVE", "https://www.paknsave.co.nz/shop/category/fridge-deli-and-eggs/eggs?pg=1"}};
		StringBuilder report = new StringBuilder("Eggs category live probe (1 page load per chain, <=3 scrolls)\n");
		ChainScraperConfig pacing = ChainScraperConfig.builder().pageDelayMinMillis(8000).pageDelayMaxMillis(15000).build();

		try (Playwright playwright = Playwright.create();
				Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
			boolean first = true;
			boolean stop = false;
			for (String[] t : targets) {
				if (stop) {
					report.append("\n== ").append(t[0]).append(": SKIPPED (previous chain blocked/errored)\n");
					continue;
				}
				if (!first) {
					ScrapePacer.pauseBetweenPages(pacing);
				}
				first = false;
				stop = probeOne(browser, t[0], t[1], report);
			}
		} finally {
			Path out = Path.of("build", "eggs-probe.txt");
			Files.createDirectories(out.getParent());
			Files.writeString(out, report.toString());
		}
	}

	/** @return true when the load errored or looks blocked (caller must not load further pages). */
	private boolean probeOne(Browser browser, String chain, String url, StringBuilder r) {
		r.append("\n== ").append(chain).append("\nURL: ").append(url).append('\n');
		long t0 = System.currentTimeMillis();
		try (BrowserContext context = browser.newContext()) {
			Page page = context.newPage();
			page.setDefaultTimeout(30_000);
			Response resp = page.navigate(url);
			int status = resp == null ? -1 : resp.status();
			try {
				page.waitForSelector(CARD, new Page.WaitForSelectorOptions().setTimeout(5000));
			} catch (RuntimeException ignored) {
				// zero cards is a legitimate outcome, reported below
			}
			long loadMs = System.currentTimeMillis() - t0;
			String title = page.title();
			boolean blocked = status == 403 || status == 429 || status == 503
					|| title.toLowerCase().contains("just a moment") || title.toLowerCase().contains("attention required");
			r.append("HTTP status: ").append(status).append("; page title: ").append(title)
					.append("; load time (navigate + wait for first card): ").append(loadMs).append(" ms\n");
			if (blocked) {
				r.append("RESULT: BLOCKED / challenge detected, stopping, no retry.\n");
				return true;
			}

			Set<String> codes = codes(page);
			int tiles = page.locator(CARD).count();
			r.append("Tiles our selector sees on initial load: ").append(tiles)
					.append(" (distinct native codes: ").append(codes.size()).append(")\n");

			String body = page.locator("body").innerText();
			List<String> totals = new ArrayList<>();
			Matcher m = TOTAL_TEXT.matcher(body);
			while (m.find() && totals.size() < 5) {
				totals.add(m.group(1).strip());
			}
			r.append("Page's own count-like text: ").append(totals.isEmpty() ? "none found" : totals).append('\n');

			Object pag = page.evaluate("() => Array.from(document.querySelectorAll('a[href*=\"pg=\"], [data-testid*=\"pagination\"] *, nav[aria-label*=\"agination\"] *, button'))"
					+ ".map(e => (e.getAttribute('data-testid')||'') + '|' + (e.getAttribute('href')||'') + '|' + (e.innerText||'').trim().slice(0,30))"
					+ ".filter(s => /pg=|pagination|next|page|load more|show more/i.test(s)).slice(0,15)");
			r.append("Pagination / load-more elements: ").append(pag).append('\n');

			int last = tiles;
			Set<String> allCodes = new LinkedHashSet<>(codes);
			for (int i = 1; i <= 3; i++) {
				page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
				page.waitForTimeout(2500);
				int now = page.locator(CARD).count();
				allCodes.addAll(codes(page));
				r.append("After scroll ").append(i).append(": tiles=").append(now).append('\n');
				last = now;
			}
			r.append("Tiles after 3 scrolls: ").append(last).append(" (distinct codes seen across scrolls: ")
					.append(allCodes.size()).append(")\n");
			r.append("Interpretation: ");
			if (last > tiles) {
				r.append("LAZY LOAD - scrolling revealed ").append(last - tiles).append(" more tile(s); first-view read misses them.\n");
			} else {
				r.append("scrolling added no tiles; see claimed totals / pagination above to decide whether the category is paginated (pg=2...) or the page is complete.\n");
			}
			return false;
		} catch (RuntimeException e) {
			r.append("RESULT: page load/probe ERROR (no retry): ").append(e.getClass().getSimpleName()).append(": ")
					.append(String.valueOf(e.getMessage()).lines().findFirst().orElse("")).append('\n');
			return true;
		}
	}

	private Set<String> codes(Page page) {
		Set<String> codes = new LinkedHashSet<>();
		@SuppressWarnings("unchecked")
		List<String> ids = (List<String>) page.evaluate(
				"() => Array.from(document.querySelectorAll(\"" + CARD + "\")).map(e => e.getAttribute('data-testid') || '')");
		for (String id : ids) {
			Matcher m = NATIVE_CODE.matcher(id);
			if (m.matches()) {
				codes.add(m.group(1));
			}
		}
		return codes;
	}
}
