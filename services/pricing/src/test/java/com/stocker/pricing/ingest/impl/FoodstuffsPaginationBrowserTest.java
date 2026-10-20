package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real Foodstuffs scraper on a real (headless Firefox) page, but against saved HTML served from localhost:
 * no supermarket is contacted. Skipped when Playwright's Firefox is not installed.
 */
class FoodstuffsPaginationBrowserTest {

	private static Playwright playwright;
	private static Browser browser;
	private static HttpServer server;
	private static final Map<String, String> PAGES = Map.of(
			"/shop/category/milk?pg=1", "foodstuffs-milk-pg1.html",
			"/shop/category/milk?pg=2", "foodstuffs-milk-pg2.html",
			"/shop/category/eggs?pg=1", "foodstuffs-eggs-single.html",
			"/shop/category/veg?pg=1", "foodstuffs-veg-pg1.html",
			"/shop/category/veg?pg=2", "foodstuffs-veg-pg2-no-count.html",
			"/shop/category/veg?pg=3", "foodstuffs-veg-pg3.html");
	private static final List<String> requests = new CopyOnWriteArrayList<>();
	private static volatile int statusForPg2 = 200;

	@BeforeAll
	static void start() throws Exception {
		try {
			playwright = Playwright.create();
			browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
		} catch (RuntimeException e) {
			assumeTrue(false, "Playwright Firefox not available: " + e.getMessage());
		}
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String key = exchange.getRequestURI().getPath() + "?" + exchange.getRequestURI().getQuery();
			if (!key.contains("pg=")) {
				key = exchange.getRequestURI().getPath() + "?pg=1";
			}
			if (key.startsWith("/favicon")) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			requests.add(key);
			String file = PAGES.get(key);
			boolean blocked = key.endsWith("pg=2") && statusForPg2 != 200;
			byte[] body = (blocked ? "blocked" : Files.readString(Path.of("src/test/resources/ingest", file)))
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(file == null ? 404 : blocked ? statusForPg2 : 200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stop() {
		if (server != null) {
			server.stop(0);
		}
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
	}

	@BeforeEach
	void reset() {
		requests.clear();
		statusForPg2 = 200;
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private ScrapeResult scrape(List<String> urls, int maxPages, AtomicInteger extraGate) {
		ChainScraperConfig config = ChainScraperConfig.builder().storeId("").storeName("").categoryUrls(urls)
				.maxPagesPerCategory(maxPages).navigationTimeoutSeconds(15).extraPageGate(() -> extraGate.getAndDecrement() > 0)
				.build();
		return new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1").scrape(browser, config);
	}

	@Test
	void walksAllPagesOfAMultiPageCategory() {
		ScrapeResult result = scrape(List.of(url("/shop/category/milk?pg=1")), 8, new AtomicInteger(10));

		assertThat(result.getProducts()).hasSize(5);
		assertThat(requests).containsExactly("/shop/category/milk?pg=1", "/shop/category/milk?pg=2");
		assertThat(result.getWarnings()).isEmpty();
	}

	@Test
	void aMiddlePageWithoutTheCountLineIsStillWalkedAndTheLastPageIsReached() {
		ScrapeResult result = scrape(List.of(url("/shop/category/veg?pg=1")), 8, new AtomicInteger(10));

		assertThat(result.getProducts()).hasSize(5);
		assertThat(requests).containsExactly("/shop/category/veg?pg=1", "/shop/category/veg?pg=2", "/shop/category/veg?pg=3");
		assertThat(result.getWarnings()).isEmpty();
	}

	@Test
	void aSinglePageCategoryCostsExactlyOnePageAndNeverAsksForAnother() {
		AtomicInteger gate = new AtomicInteger(10);
		ScrapeResult result = scrape(List.of(url("/shop/category/eggs?pg=1")), 8, gate);

		assertThat(result.getProducts()).hasSize(2);
		assertThat(requests).containsExactly("/shop/category/eggs?pg=1");
		assertThat(gate.get()).isEqualTo(10);
	}

	@Test
	void theBudgetGateStopsTheWalkAndAWarningSaysWhatIsMissing() {
		ScrapeResult result = scrape(List.of(url("/shop/category/milk?pg=1")), 8, new AtomicInteger(0));

		assertThat(result.getProducts()).hasSize(3);
		assertThat(requests).hasSize(1);
		assertThat(result.getWarnings()).singleElement().asString().contains("3 of 5");
	}

	@Test
	void aBlockedLaterPageStopsTheCategoryAndKeepsTheTilesAlreadyRead() {
		statusForPg2 = 429;
		List<String> urls = new ArrayList<>(List.of(url("/shop/category/milk?pg=1")));

		ScrapeResult result = scrape(urls, 8, new AtomicInteger(10));

		assertThat(result.getProducts()).hasSize(3);
		assertThat(requests).containsExactly("/shop/category/milk?pg=1", "/shop/category/milk?pg=2");
		assertThat(result.getWarnings()).singleElement().asString().contains("3 of 5").contains("429");
	}
}
