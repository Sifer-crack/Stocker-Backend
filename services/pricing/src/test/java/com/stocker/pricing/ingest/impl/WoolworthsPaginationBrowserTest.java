package com.stocker.pricing.ingest.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.CrawlBreaker;
import com.stocker.pricing.ingest.model.CategoryResultSink;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real Woolworths scraper on a real (headless Firefox) page against a localhost server that mimics the live paging
 * (36 listed products per page, "A to B of M products", {@code ?page=N}; captured 2026-10-20). Tiles are the real
 * captured tile markup with the stockcode and name varied; the pagination nav is the real captured one. No supermarket
 * is contacted. Skipped without Playwright's Firefox.
 */
class WoolworthsPaginationBrowserTest {

	private static final int PAGE_SIZE = 36;
	/** Listed products per category path. */
	private static final Map<String, Integer> TOTALS = Map.of("/a", 72, "/b", 20, "/c", 108, "/blocked2", 72);
	private static final String REAL_NAME = "Oreo Creme Filled Biscuits Original Minis 10 Pack - 204g";

	private static Playwright playwright;
	private static Browser browser;
	private static HttpServer server;
	private static String tile;
	private static String nav;
	private static final List<String> requests = new CopyOnWriteArrayList<>();
	private static volatile int statusForPage2 = 200;

	private ListAppender<ILoggingEvent> appender;
	private Logger scraperLogger;

	@BeforeAll
	static void start() throws Exception {
		try {
			playwright = Playwright.create();
			browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
		} catch (RuntimeException e) {
			assumeTrue(false, "Playwright Firefox not available: " + e.getMessage());
		}
		String real = Files.readString(Path.of("src/test/resources/ingest/woolworths-real-cards.html"));
		int from = real.indexOf("<div class=\"product-tile_imgAndTextArea_");
		tile = real.substring(from, real.indexOf("\n", from));
		nav = Files.readString(Path.of("src/test/resources/ingest/woolworths-pagination-nav.html"));
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String query = exchange.getRequestURI().getQuery();
			if (path.startsWith("/favicon")) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			requests.add(query == null ? path : path + "?" + query);
			int page = query != null && query.startsWith("page=") ? Integer.parseInt(query.substring(5)) : 1;
			int total = TOTALS.getOrDefault(path, 0);
			boolean blocked = page == 2 && statusForPage2 != 200 && path.equals("/blocked2");
			byte[] body = (blocked ? "blocked" : render(path, page, total)).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(blocked ? statusForPage2 : total == 0 ? 404 : 200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
	}

	private static String render(String path, int page, int total) {
		int first = (page - 1) * PAGE_SIZE + 1;
		int last = Math.min(total, page * PAGE_SIZE);
		StringBuilder sb = new StringBuilder("<html><body><div class=\"search-result-count_searchResultsCount__KGBXK\">")
				.append(first).append(" to ").append(last).append(" of ").append(total).append(" products</div><div class=\"grid\">");
		for (int i = first; i <= last; i++) {
			String id = String.valueOf(900000 + (path.hashCode() & 0xfff) * 1000 + i);
			sb.append(tile.replace("317455", id).replace(REAL_NAME, "Product " + path.substring(1) + " " + i)).append('\n');
		}
		return sb.append("</div>").append(nav).append("</body></html>").toString();
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
		statusForPage2 = 200;
		scraperLogger = (Logger) LoggerFactory.getLogger(WoolworthsNzChainScraper.class);
		appender = new ListAppender<>();
		appender.start();
		scraperLogger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		scraperLogger.detachAppender(appender);
	}

	private List<String> warns() {
		return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private record Saved(String url, int count) {
	}

	private ChainScraperConfig.ChainScraperConfigBuilder base(List<String> urls, int maxPages, AtomicInteger extraGate,
			List<Saved> saved) {
		return ChainScraperConfig.builder().storeId("").storeName("").categoryUrls(urls).maxPagesPerCategory(maxPages)
				.navigationTimeoutSeconds(15).extraPageGate(() -> extraGate.getAndDecrement() > 0)
				.categoryResultSink((u, products) -> saved.add(new Saved(u, products.size())));
	}

	private ScrapeResult scrape(ChainScraperConfig config) {
		return new WoolworthsNzChainScraper(com.stocker.pricing.ingest.model.LeanPageOptions.OFF, "http://127.0.0.1")
				.scrape(browser, config);
	}

	@Test
	void walksEveryPageOfAMultiPageCategoryAndSavesItOnce() {
		List<Saved> saved = new ArrayList<>();

		ScrapeResult result = scrape(base(List.of(url("/a")), 10, new AtomicInteger(10), saved).build());

		assertThat(result.getProducts()).hasSize(72);
		assertThat(result.getProducts()).extracting(RawProduct::getNativeProductCode).doesNotHaveDuplicates();
		assertThat(requests).containsExactly("/a", "/a?page=2");
		assertThat(result.getWarnings()).isEmpty();
		assertThat(warns()).isEmpty();
		assertThat(saved).containsExactly(new Saved(url("/a"), 72));
	}

	@Test
	void aSinglePageCategoryCostsExactlyOnePageAndNeverAsksForAnother() {
		AtomicInteger gate = new AtomicInteger(10);
		List<Saved> saved = new ArrayList<>();

		ScrapeResult result = scrape(base(List.of(url("/b")), 10, gate, saved).build());

		assertThat(result.getProducts()).hasSize(20);
		assertThat(requests).containsExactly("/b");
		assertThat(gate.get()).isEqualTo(10);
		assertThat(saved).containsExactly(new Saved(url("/b"), 20));
	}

	@Test
	void theSinkHearsOncePerCategoryWithItsOwnProducts() {
		List<Saved> saved = new ArrayList<>();

		ScrapeResult result = scrape(base(List.of(url("/a"), url("/b")), 10, new AtomicInteger(10), saved).build());

		assertThat(result.getProducts()).hasSize(92);
		assertThat(saved).containsExactly(new Saved(url("/a"), 72), new Saved(url("/b"), 20));
	}

	@Test
	void aBlockedSecondPageKeepsPageOneAndWarnsHowMuchIsMissing() {
		statusForPage2 = 429;
		List<Saved> saved = new ArrayList<>();

		ScrapeResult result = scrape(base(List.of(url("/blocked2")), 10, new AtomicInteger(10), saved).build());

		assertThat(result.getProducts()).hasSize(36);
		assertThat(requests).containsExactly("/blocked2", "/blocked2?page=2");
		assertThat(result.getWarnings()).singleElement().asString().contains("36 of 72").contains("429");
		assertThat(warns()).anyMatch(w -> w.contains("36 of 72"));
		assertThat(saved).containsExactly(new Saved(url("/blocked2"), 36));
	}

	@Test
	void aServerErrorOnThePageTwoStopsTheWalkToo() {
		statusForPage2 = 503;

		ScrapeResult result = scrape(base(List.of(url("/blocked2")), 10, new AtomicInteger(10), new ArrayList<>()).build());

		assertThat(result.getProducts()).hasSize(36);
		assertThat(result.getWarnings()).singleElement().asString().contains("36 of 72");
	}

	@Test
	void theBreakerOpenedByThePageTwoStopsTheNextCategory() {
		statusForPage2 = 429;
		CrawlBreaker breaker = new CrawlBreaker(() -> 1, () -> Duration.ofHours(6), () -> Duration.ofHours(24), Clock.systemUTC());
		List<Saved> saved = new ArrayList<>();

		ScrapeResult result = scrape(base(List.of(url("/blocked2"), url("/b")), 10, new AtomicInteger(10), saved)
				.crawlBrake(breaker.forChain(ChainId.WOOLWORTHS)).build());

		assertThat(requests).containsExactly("/blocked2", "/blocked2?page=2");
		assertThat(breaker.isOpen(ChainId.WOOLWORTHS)).isTrue();
		assertThat(result.getProducts()).hasSize(36);
		assertThat(result.getWarnings()).anyMatch(w -> w.contains("Circuit breaker open"));
	}

	@Test
	void thePageCapStopsTheWalkAndWarns() {
		ScrapeResult result = scrape(base(List.of(url("/c")), 2, new AtomicInteger(10), new ArrayList<>()).build());

		assertThat(result.getProducts()).hasSize(72);
		assertThat(requests).containsExactly("/c", "/c?page=2");
		assertThat(result.getWarnings()).singleElement().asString().contains("72 of 108").contains("page cap");
	}

	@Test
	void theDailyBudgetGateStopsTheWalkAndWarns() {
		ScrapeResult result = scrape(base(List.of(url("/a")), 10, new AtomicInteger(0), new ArrayList<>()).build());

		assertThat(result.getProducts()).hasSize(36);
		assertThat(requests).hasSize(1);
		assertThat(result.getWarnings()).singleElement().asString().contains("36 of 72").contains("budget");
	}

	@Test
	void threePagesAreWalkedWhenTheCapAllows() {
		ScrapeResult result = scrape(base(List.of(url("/c")), 10, new AtomicInteger(10), new ArrayList<>()).build());

		assertThat(result.getProducts()).hasSize(108);
		assertThat(requests).containsExactly("/c", "/c?page=2", "/c?page=3");
		assertThat(result.getWarnings()).isEmpty();
	}
}
