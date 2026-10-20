package com.stocker.pricing.ingest.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.parse.CategoryPagination;
import com.stocker.pricing.ingest.parse.ChainPageTemplate;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Page templates and lean page loads on a real (headless Firefox) page. Everything is served from localhost out of the
 * saved fixtures (services/pricing/src/test/resources/ingest/**): no supermarket is contacted. Skipped when Playwright's
 * Firefox is not installed.
 */
class TemplatePageBrowserTest {

	private static final Path FIXTURES = Path.of("src/test/resources/ingest");
	private static final LeanPageOptions LEAN = new LeanPageOptions(true, Set.of("image", "media", "font"),
			List.of("localhost", "googletagmanager"), false);

	private static Playwright playwright;
	private static Browser browser;
	private static HttpServer server;
	/** "host/path" of every request that reached the server (host = the Host header, so 127.0.0.1 vs localhost). */
	private static final List<String> requests = new CopyOnWriteArrayList<>();
	/** path (with query) -> fixture file name; or a literal body when stored in {@link #DYNAMIC}. */
	private static final Map<String, String> FILES = new ConcurrentHashMap<>();
	private static final Map<String, String> DYNAMIC = new ConcurrentHashMap<>();
	private static final Map<String, Integer> STATUS = new ConcurrentHashMap<>();

	private ListAppender<ILoggingEvent> appender;
	private Logger ingestLogger;
	private Level previousLevel;

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
			String key = exchange.getRequestURI().getQuery() == null ? path : path + "?" + exchange.getRequestURI().getQuery();
			if (path.startsWith("/favicon")) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			String host = String.valueOf(exchange.getRequestHeaders().getFirst("Host")).replaceFirst(":\\d+$", "");
			requests.add(host + path);
			String body = DYNAMIC.get(key);
			String contentType = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css"
					: path.endsWith(".png") ? "image/png" : "text/html; charset=utf-8";
			if (body == null && FILES.containsKey(key)) {
				body = read(FILES.get(key)).replace("{{PORT}}", String.valueOf(server.getAddress().getPort()));
			}
			if (body == null && (path.endsWith(".png") || path.endsWith(".css") || path.equals("/googletagmanager.js"))) {
				body = "x";
			}
			byte[] bytes = (body == null ? "missing" : body).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(body == null ? 404 : STATUS.getOrDefault(key, 200), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	private static String read(String name) {
		try {
			return Files.readString(FIXTURES.resolve(name));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
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
		FILES.clear();
		DYNAMIC.clear();
		STATUS.clear();
		ingestLogger = (Logger) LoggerFactory.getLogger("com.stocker.pricing.ingest");
		previousLevel = ingestLogger.getLevel();
		ingestLogger.setLevel(Level.INFO);
		appender = new ListAppender<>();
		appender.start();
		ingestLogger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		ingestLogger.detachAppender(appender);
		ingestLogger.setLevel(previousLevel);
	}

	private String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private List<String> logs(Level level) {
		return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
	}

	private ChainScraperConfig config(List<String> urls) {
		return ChainScraperConfig.builder().storeId("").storeName("").categoryUrls(urls).maxPagesPerCategory(8)
				.navigationTimeoutSeconds(15).extraPageGate(() -> true).build();
	}

	private ScrapeResult newWorld(LeanPageOptions options, String... paths) {
		List<String> urls = java.util.Arrays.stream(paths).map(this::url).toList();
		return new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1", options).scrape(browser, config(urls));
	}

	// ---------------------------------------------------------------- lean loads

	@Test
	void withoutLeanPagesEveryRequestIsMade() {
		FILES.put("/lean", "lean-page.html");
		FILES.put("/app.js", "lean-app.js");

		ScrapeResult result = newWorld(LeanPageOptions.OFF, "/lean");

		assertThat(result.getProducts()).hasSize(2);
		assertThat(requests).contains("127.0.0.1/banner.png", "localhost/pixel.png", "localhost/googletagmanager.js",
				"127.0.0.1/site.css", "127.0.0.1/app.js");
	}

	@Test
	void leanPagesDropImagesAndTrackersButStillRenderTheGridWithTheChainsOwnScript() {
		FILES.put("/lean", "lean-page.html");
		FILES.put("/app.js", "lean-app.js");

		ScrapeResult result = newWorld(LEAN, "/lean");

		assertThat(result.getProducts()).extracting(p -> p.getNativeProductCode()).containsExactly("5000001", "5000002");
		assertThat(result.getProducts().get(0).getImageUrl()).endsWith("/img1.png");      // the src attribute is still read
		assertThat(result.getWarnings()).isEmpty();
		assertThat(requests).contains("127.0.0.1/lean", "127.0.0.1/app.js", "127.0.0.1/site.css");
		assertThat(requests).noneMatch(r -> r.endsWith(".png")).noneMatch(r -> r.startsWith("localhost/"));
		assertThat(logs(Level.INFO)).anyMatch(l -> l.matches("NEWWORLD page url=\\S+/lean tiles=2 claimed=2 blocked-requests=[5-9]/\\d+"));
	}

	@Test
	void stylesheetsAreOnlyBlockedWhenAskedAndExtractionStillWorksWithoutThem() {
		FILES.put("/lean", "lean-page.html");
		FILES.put("/app.js", "lean-app.js");
		LeanPageOptions noCss = new LeanPageOptions(true, Set.of("image", "media", "font", "stylesheet"), List.of(), false);

		ScrapeResult result = newWorld(noCss, "/lean");

		assertThat(result.getProducts()).hasSize(2);
		assertThat(requests).doesNotContain("127.0.0.1/site.css");
	}

	@Test
	void theRequestLogListsHostsResourceTypesAndWhatWasAborted() {
		FILES.put("/lean", "lean-page.html");
		FILES.put("/app.js", "lean-app.js");
		LeanPageOptions logging = new LeanPageOptions(true, Set.of("image", "media", "font"), List.of("localhost"), true);

		newWorld(logging, "/lean");

		assertThat(logs(Level.INFO)).anyMatch(l -> l.startsWith("NEWWORLD requests url=")
				&& l.contains("localhost [") && l.contains("script 1 (all aborted)") && l.contains("image 1 (all aborted)")
				&& l.contains("127.0.0.1 [") && l.contains("document 1"));
	}

	@Test
	void requestLoggingAloneBlocksNothing() {
		FILES.put("/lean", "lean-page.html");
		FILES.put("/app.js", "lean-app.js");

		newWorld(new LeanPageOptions(false, Set.of("image"), List.of("localhost"), true), "/lean");

		assertThat(requests).contains("127.0.0.1/banner.png", "localhost/googletagmanager.js");
		assertThat(logs(Level.INFO)).anyMatch(l -> l.startsWith("NEWWORLD requests url=") && !l.contains("aborted"));
	}

	@Test
	void aBlockedLaterPageIsStillReportedWithLeanPagesOnAndKeepsTheTilesAlreadyRead() {
		FILES.put("/shop/category/milk?pg=1", "foodstuffs-milk-pg1.html");
		FILES.put("/shop/category/milk?pg=2", "foodstuffs-milk-pg2.html");
		STATUS.put("/shop/category/milk?pg=2", 429);

		ScrapeResult result = newWorld(LEAN, "/shop/category/milk?pg=1");

		assertThat(result.getProducts()).hasSize(3);
		assertThat(result.getWarnings()).singleElement().asString().contains("3 of 5").contains("429");
	}

	// ---------------------------------------------------------------- template self-check

	@Test
	void aMissingMandatoryFieldGivesAWarnNamingChainFieldAndUrl() {
		FILES.put("/shop/category/milk?pg=1", "foodstuffs-no-title.html");

		ScrapeResult result = newWorld(LEAN, "/shop/category/milk?pg=1");

		assertThat(result.getProducts()).isEmpty();      // no name -> extractor skips the tiles, as before
		assertThat(logs(Level.WARN)).anyMatch(l -> l.startsWith("NEWWORLD: page template field 'name'")
				&& l.contains("[data-testid='product-title']") && l.contains("2 product tiles")
				&& l.contains(url("/shop/category/milk?pg=1")));
	}

	@Test
	void aPageWithoutTilesStillReportsTheTemplatesTileSelectorAndTheUrl() {
		DYNAMIC.put("/empty", "<html><body>nothing here</body></html>");

		ScrapeResult result = newWorld(LEAN, "/empty");

		assertThat(result.getZeroResultCategoryUrls()).containsExactly(url("/empty"));
		assertThat(logs(Level.WARN)).anyMatch(l -> l.contains("NEWWORLD") && l.contains(url("/empty"))
				&& l.contains("div[itemtype='https://schema.org/Product']"));
	}

	private static String gridOf(int tiles) {
		StringBuilder html = new StringBuilder("<html><body><p>Showing 1 - " + tiles + " of " + tiles + " products</p>");
		for (int i = 0; i < tiles; i++) {
			html.append("<div itemscope itemtype=\"https://schema.org/Product\" data-testid=\"product-70000").append(i)
					.append("-EA-000\"><p data-testid=\"product-title\">P").append(i)
					.append("</p><meta itemprop=\"price\" content=\"1.00\"></div>");
		}
		return html.append("</body></html>").toString();
	}

	@Test
	void aTileCountDropBelowEightyPercentOfTheLastRunWarns() {
		DYNAMIC.put("/shrinking", gridOf(10));
		FoodstuffsChainScraper scraper = new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1", LEAN);

		scraper.scrape(browser, config(List.of(url("/shrinking"))));
		assertThat(logs(Level.WARN)).noneMatch(l -> l.contains("tiles dropped"));

		DYNAMIC.put("/shrinking", gridOf(7));
		scraper.scrape(browser, config(List.of(url("/shrinking"))));

		assertThat(logs(Level.WARN)).anyMatch(l -> l.contains("tiles dropped from 10 to 7") && l.contains(url("/shrinking")));
	}

	// ---------------------------------------------------------------- per-chain templates on saved fixtures

	@Test
	void paknsaveSplitDollarAndCentsPricesAreReadByTheSharedFoodstuffsTemplate() {
		FILES.put("/eggs", "foodstuffs-paknsave-dollars.html");

		ScrapeResult result = new FoodstuffsChainScraper(ChainId.PAKNSAVE, "http://127.0.0.1", LEAN)
				.scrape(browser, config(List.of(url("/eggs"))));

		assertThat(result.getProducts()).extracting(p -> p.getPrice().toPlainString()).containsExactly("7.49", "4.19");
		assertThat(result.getProducts()).extracting(p -> p.getChainId()).containsOnly("paknsave");
		assertThat(result.getProducts().get(0).getBrand()).isEqualTo("12pk");
		assertThat(logs(Level.WARN)).noneMatch(l -> l.contains("page template field"));
	}

	@Test
	void aCentsSpanThatStartsWithItsOwnDotIsNotJoinedIntoADoubledSeparator() {
		FILES.put("/butter", "foodstuffs-paknsave-doubled-dot.html");

		ScrapeResult result = new FoodstuffsChainScraper(ChainId.PAKNSAVE, "http://127.0.0.1", LEAN)
				.scrape(browser, config(List.of(url("/butter"))));

		assertThat(result.getProducts()).extracting(p -> p.getPrice().toPlainString()).containsExactly("2.99", "4.19");
		assertThat(logs(Level.WARN)).noneMatch(l -> l.contains("unparsable price"));
	}

	@Test
	void woolworthsTemplateReadsNamePriceStockcodeAndImage() {
		FILES.put("/milk", "woolworths-milk.html");

		ScrapeResult result = new WoolworthsNzChainScraper(LEAN, "http://127.0.0.1")
				.scrape(browser, config(List.of(url("/milk"))));

		assertThat(result.getProducts()).extracting(p -> p.getNativeProductCode()).containsExactly("131002", "131003");
		assertThat(result.getProducts()).extracting(p -> p.getName()).containsExactly("Milk 2l", "Anchor Light Milk 1l");
		assertThat(result.getProducts()).extracting(p -> p.getPrice().toPlainString()).containsExactly("3.49", "2.10");
		assertThat(result.getProducts().get(0).getImageUrl()).isEqualTo("https://img/131002.png");
		assertThat(logs(Level.WARN)).isEmpty();
		assertThat(logs(Level.INFO)).anyMatch(l -> l.startsWith("WOOLWORTHS page url=") && l.contains("tiles=2 claimed=2"));
	}

	@Test
	void woolworthsMissingPriceSelectorWarnsByName() {
		DYNAMIC.put("/milk", read("woolworths-milk.html").replace("product-price_value_", "product-price_amount_"));

		ScrapeResult result = new WoolworthsNzChainScraper(LEAN, "http://127.0.0.1")
				.scrape(browser, config(List.of(url("/milk"))));

		assertThat(result.getProducts()).isEmpty();
		assertThat(logs(Level.WARN)).anyMatch(l -> l.startsWith("WOOLWORTHS: page template field 'price'")
				&& l.contains("product-price_value_") && l.contains(url("/milk")));
	}

	private static final String COUNT = "<div>1 to 3 of 3 products</div>";
	private static final String SHELL = "<div class=\"product-tile_imgAndTextArea__LeaM_\"></div>";

	@Test
	void woolworthsRealCapturedTilesParse() {
		FILES.put("/biscuits", "woolworths-real-cards.html");

		ScrapeResult result = new WoolworthsNzChainScraper(LEAN, "http://127.0.0.1")
				.scrape(browser, config(List.of(url("/biscuits"))));

		assertThat(result.getProducts()).extracting(p -> p.getNativeProductCode()).hasSize(3).doesNotContainNull();
		assertThat(result.getProducts().get(0).getNativeProductCode()).isEqualTo("317455");
		assertThat(result.getProducts().get(0).getName()).isEqualTo("Oreo Creme Filled Biscuits Original Minis 10 Pack - 204g");
		assertThat(result.getProducts().get(0).getPrice().toPlainString()).isEqualTo("4.99");
		assertThat(result.getProducts().get(0).getImageUrl()).contains("317455.jpg");
		assertThat(result.getZeroResultCategoryUrls()).isEmpty();
		assertThat(logs(Level.WARN)).isEmpty();
	}

	@Test
	void woolworthsEmptyShellTilesGiveOneSummaryWarnPerPage() {
		DYNAMIC.put("/biscuits", read("woolworths-real-cards.html").replace("</div></body>", SHELL + SHELL + "</div></body>"));

		ScrapeResult result = new WoolworthsNzChainScraper(LEAN, "http://127.0.0.1")
				.scrape(browser, config(List.of(url("/biscuits"))));

		assertThat(result.getProducts()).hasSize(3);
		assertThat(result.getZeroResultCategoryUrls()).isEmpty();
		assertThat(logs(Level.WARN)).hasSize(1).first().satisfies(l -> assertThat(l)
				.startsWith("WOOLWORTHS: 2 of 5 cards unusable at " + url("/biscuits"))
				.contains("name 2, code 2, price 2").contains("template selectors:").contains("product-tile_description_"));
	}

	@Test
	void woolworthsTilesWithoutAnyProductCountAsZeroResultAndWarnTemplateStale() {
		DYNAMIC.put("/biscuits", "<html><body>" + COUNT + SHELL + SHELL + SHELL + "</body></html>");

		ScrapeResult result = new WoolworthsNzChainScraper(LEAN, "http://127.0.0.1")
				.scrape(browser, config(List.of(url("/biscuits"))));

		assertThat(result.getProducts()).isEmpty();
		assertThat(result.getZeroResultCategoryUrls()).containsExactly(url("/biscuits"));
		assertThat(logs(Level.WARN)).hasSize(3);
		assertThat(logs(Level.WARN)).anyMatch(l -> l.contains("3 of 3 cards unusable"));
		assertThat(logs(Level.WARN)).anyMatch(l -> l.startsWith("WOOLWORTHS: page template field 'name'")
				&& l.contains("also missing: 'code'"));
		assertThat(logs(Level.WARN)).anyMatch(l -> l.contains("template stale or tiles not hydrated"));
	}

	@Test
	void woolworthsWaitsForTilesToHydrate() {
		String real = read("woolworths-real-cards.html");
		String filled = real.substring(real.indexOf("<div class=\"grid\">") + "<div class=\"grid\">".length(),
				real.indexOf("</div></body>"));
		String script = "<script>setTimeout(function(){document.getElementById('g').innerHTML=" + toJsString(filled)
				+ ";},2000);</script>";
		DYNAMIC.put("/biscuits", "<html><body>" + COUNT + "<div id=\"g\">" + SHELL + SHELL + SHELL + "</div>" + script + "</body></html>");

		ScrapeResult result = new WoolworthsNzChainScraper(LEAN, "http://127.0.0.1")
				.scrape(browser, config(List.of(url("/biscuits"))));

		assertThat(result.getProducts()).hasSize(3);
		assertThat(logs(Level.WARN)).isEmpty();
	}

	private static String toJsString(String html) {
		return "\"" + html.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\"";
	}

	// ---------------------------------------------------------------- probe pages (real captures, text only)

	private record Probe(String url, int tiles, String showing, String navMarkup, String bodyText) {
	}

	private static Probe probe(String name) {
		String text = read("probe/" + name);
		String url = line(text, "URL: ");
		int tiles = Integer.parseInt(line(text, "TILES: "));
		String showing = line(text, "SHOWING: ");
		Matcher nav = Pattern.compile("PAGINATION MARKUP: \\[(.*)\\]\\s*$", Pattern.MULTILINE).matcher(text);
		assertThat(nav.find()).isTrue();
		String body = text.substring(text.indexOf("BODY TEXT (first 6000 chars):") + "BODY TEXT (first 6000 chars):".length());
		return new Probe(url, tiles, showing, nav.group(1), body.strip());
	}

	private static String line(String text, String prefix) {
		return text.lines().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow().substring(prefix.length()).trim();
	}

	/** The probe's recorded page: its tile count, its visible text and its pagination nav, tiles in minimal valid markup. */
	private String probeHtml(Probe probe, boolean withShowingText) {
		StringBuilder html = new StringBuilder("<html><body>");
		html.append(probe.navMarkup());
		html.append("<div style=\"white-space:pre-wrap\">")
				// the capture kept only the first 6000 characters of text, which on some pages ends before the "Showing" line
				// (recorded separately in the SHOWING header): append that line, exactly as the live page shows it
				.append((probe.bodyText().replaceAll("(?i)showing\\s+\\d+\\s*-\\s*\\d+ of \\d+ products", "Items")
						+ (withShowingText ? "\n\n" + probe.showing() + "\n" : ""))
						.replace("&", "&amp;").replace("<", "&lt;"))
				.append("</div>");
		for (int i = 0; i < probe.tiles(); i++) {
			html.append("<div itemscope itemtype=\"https://schema.org/Product\" data-testid=\"product-").append(4000000 + i)
					.append("-EA-000\"><p data-testid=\"product-title\">Tile ").append(i)
					.append("</p><span data-testid=\"price-dollars\">3</span><span data-testid=\"price-cents\">89</span></div>");
		}
		return html.append("</body></html>").toString();
	}

	private TemplatedPageLoader.PageRead readProbe(ChainId chain, Probe probe, boolean withShowingText) {
		try (Page page = browser.newContext().newPage()) {
			page.setContent(probeHtml(probe, withShowingText));
			TemplatedPageLoader loader = new TemplatedPageLoader(chain, ChainPageTemplate.foodstuffs(chain),
					new LeanPageRouter(chain, LeanPageOptions.OFF, "https://x"), new PageCountMonitor(), false);
			return loader.readCurrent(page, probe.url());
		}
	}

	@Test
	void realNewWorldAndPaknsaveProbePagesYieldTheRecordedTileCountShowingLineAndNav() {
		for (String[] spec : new String[][] {{"newworld-page1.txt", "NEWWORLD"}, {"newworld-page9.txt", "NEWWORLD"},
				{"paknsave-page1.txt", "PAKNSAVE"}, {"paknsave-page10.txt", "PAKNSAVE"}}) {
			Probe probe = probe(spec[0]);
			appender.list.clear();

			TemplatedPageLoader.PageRead read = readProbe(ChainId.valueOf(spec[1]), probe, true);

			assertThat(read.cards()).as(spec[0]).hasSize(probe.tiles());
			assertThat(read.showingText()).as(spec[0]).isEqualToIgnoringCase(probe.showing());
			assertThat(CategoryPagination.parse(read.showingText())).as(spec[0]).isPresent();
			assertThat(logs(Level.WARN)).as(spec[0]).isEmpty();
			assertThat(logs(Level.INFO)).as(spec[0]).anyMatch(l -> l.contains("tiles=" + probe.tiles() + " claimed="));
		}
	}

	@Test
	void probeShowingLinesGiveTheRecordedPaging() {
		assertThat(CategoryPagination.parse(probe("newworld-page1.txt").showing())).hasValueSatisfying(p -> {
			assertThat(p.total()).isEqualTo(451);
			assertThat(p.hasMore()).isTrue();
		});
		assertThat(CategoryPagination.parse(probe("newworld-page9.txt").showing()))
				.hasValueSatisfying(p -> assertThat(p.hasMore()).isFalse());
		assertThat(CategoryPagination.parse(probe("paknsave-page10.txt").showing()))
				.hasValueSatisfying(p -> assertThat(p.total()).isEqualTo(520));
	}

	@Test
	void aProbePageWithoutItsShowingLineWarnsAboutTheMissingTemplateField() {
		Probe probe = probe("newworld-page1.txt");

		TemplatedPageLoader.PageRead read = readProbe(ChainId.NEWWORLD, probe, false);

		assertThat(read.showingText()).isNull();
		assertThat(logs(Level.WARN)).singleElement().asString().startsWith("NEWWORLD: page template field 'showing'")
				.contains(probe.url()).contains("pagination nav IS present");
	}
}
