package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.CrawlBreaker;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real scrapers on a real (headless Firefox) page against a localhost server: a run of blocked responses trips the
 * breaker and the scraper makes no further request. No supermarket is contacted. Skipped without Playwright's Firefox.
 */
class CrawlBreakerBrowserTest {

	private static Playwright playwright;
	private static Browser browser;
	private static HttpServer server;
	private static final List<String> requests = new CopyOnWriteArrayList<>();
	private static volatile String retryAfter = null;

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
			String path = exchange.getRequestURI().getPath();
			if (path.startsWith("/favicon")) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			requests.add(path);
			int status = 200;
			String body;
			if (path.startsWith("/blocked/")) {
				status = path.startsWith("/blocked/403/") ? 403 : path.startsWith("/blocked/503/") ? 503 : 429;
				body = "blocked";
				if (retryAfter != null) {
					exchange.getResponseHeaders().add("Retry-After", retryAfter);
				}
			} else if (path.startsWith("/challenge/")) {
				body = "<html><head><title>Just a moment...</title></head><body>Please verify you are human</body></html>";
			} else {
				body = Files.readString(Path.of("src/test/resources/ingest/foodstuffs-eggs-single.html"));
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
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
		retryAfter = null;
	}

	private final CrawlBreaker breaker = new CrawlBreaker(() -> 3, () -> Duration.ofHours(6), () -> Duration.ofHours(24),
			Clock.systemUTC());

	private List<String> urls(String prefix, int count) {
		List<String> urls = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			urls.add("http://127.0.0.1:" + server.getAddress().getPort() + prefix + i + "?pg=1");
		}
		return urls;
	}

	private ChainScraperConfig config(ChainId chain, List<String> urls) {
		return ChainScraperConfig.builder().storeId("").storeName("").categoryUrls(urls).maxPagesPerCategory(8)
				.navigationTimeoutSeconds(15).crawlBrake(breaker.forChain(chain)).build();
	}

	@Test
	void foodstuffsStopsAfterThreeBlockedResponsesAndMakesNoFurtherRequest() {
		ScrapeResult result = new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1")
				.scrape(browser, config(ChainId.NEWWORLD, urls("/blocked/429/", 8)));

		assertThat(requests).hasSize(3);
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();
		assertThat(breaker.isOpen(ChainId.PAKNSAVE)).isFalse();
		// blocked pages are neither visited nor zero-result pages (CategoryHealth must not quarantine them)
		assertThat(result.getCategoriesVisited()).isEmpty();
		assertThat(result.getZeroResultCategoryUrls()).isEmpty();
	}

	@Test
	void a403And503CountAsBlockedToo() {
		List<String> mixed = new ArrayList<>(urls("/blocked/429/", 1));
		mixed.addAll(urls("/blocked/403/", 1));
		mixed.addAll(urls("/blocked/503/", 1));
		mixed.addAll(urls("/blocked/429/", 4));

		new FoodstuffsChainScraper(ChainId.PAKNSAVE, "http://127.0.0.1").scrape(browser, config(ChainId.PAKNSAVE, mixed));

		assertThat(requests).hasSize(3);
		assertThat(breaker.isOpen(ChainId.PAKNSAVE)).isTrue();
	}

	@Test
	void aGoodPageInTheMiddleResetsTheCounter() {
		List<String> sequence = new ArrayList<>(urls("/blocked/429/", 2));
		sequence.addAll(urls("/shop/category/eggs", 1));
		sequence.addAll(urls("/blocked/429/", 2));

		ScrapeResult result = new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1")
				.scrape(browser, config(ChainId.NEWWORLD, sequence));

		assertThat(requests).hasSize(5);
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		assertThat(result.getCategoriesVisited()).hasSize(1);
		assertThat(result.getProducts()).hasSize(2);
	}

	@Test
	void aCaptchaChallengePageCountsAsBlockedNotAsAnEmptyCategory() {
		ScrapeResult result = new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1")
				.scrape(browser, config(ChainId.NEWWORLD, urls("/challenge/", 6)));

		assertThat(requests).hasSize(3);
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();
		assertThat(result.getZeroResultCategoryUrls()).isEmpty();
		assertThat(result.getCategoriesVisited()).isEmpty();
	}

	@Test
	void retryAfterFromTheResponseLengthensTheCoolDown() {
		retryAfter = "36000";      // 10 h, longer than the 6 h default

		new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1")
				.scrape(browser, config(ChainId.NEWWORLD, urls("/blocked/429/", 3)));

		Duration cooldown = Duration.between(java.time.Instant.now(), breaker.openUntil(ChainId.NEWWORLD));
		assertThat(cooldown).isBetween(Duration.ofHours(10).minusMinutes(1), Duration.ofHours(10));
	}

	@Test
	void woolworthsStopsAfterThreeBlockedResponsesToo() {
		ScrapeResult result = new WoolworthsNzChainScraper()
				.scrape(browser, config(ChainId.WOOLWORTHS, urls("/blocked/403/", 8)));

		assertThat(requests).hasSize(3);
		assertThat(breaker.isOpen(ChainId.WOOLWORTHS)).isTrue();
		assertThat(result.getCategoriesVisited()).isEmpty();
		assertThat(result.getZeroResultCategoryUrls()).isEmpty();
	}

	@Test
	void anOpenBreakerMeansTheScraperMakesNoRequestAtAll() {
		for (int i = 0; i < 3; i++) {
			breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null);
		}

		new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1")
				.scrape(browser, config(ChainId.NEWWORLD, urls("/shop/category/eggs", 4)));

		assertThat(requests).isEmpty();
	}
}
