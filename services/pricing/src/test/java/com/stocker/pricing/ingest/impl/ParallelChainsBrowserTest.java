package com.stocker.pricing.ingest.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.CrawlBreaker;
import com.stocker.pricing.ingest.PerChainBrowsers;
import com.stocker.pricing.ingest.config.PlaywrightConfig;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Two real scrapers at the same time, each on its own headless Firefox, against a localhost server: one chain is
 * blocked and trips its own breaker, the other still reads its page. All browsers are gone after close().
 * No supermarket is contacted. Skipped without Playwright's Firefox.
 */
class ParallelChainsBrowserTest {

	@Test
	void twoChainsCrawlAtOnceOnSeparateBrowsersAndAllProcessesAreClosed() throws Exception {
		List<String> requests = new CopyOnWriteArrayList<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String page = Files.readString(Path.of("src/test/resources/ingest/foodstuffs-eggs-single.html"));
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.startsWith("/favicon")) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			requests.add(path);
			boolean blocked = path.startsWith("/blocked/");
			byte[] bytes = (blocked ? "blocked" : page).getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(blocked ? 429 : 200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		PerChainBrowsers browsers = new PerChainBrowsers(PlaywrightConfig::launchOwned);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		long firefoxBefore = firefoxProcesses();
		try {
			try {
				browsers.forChain(ChainId.NEWWORLD);
			} catch (RuntimeException e) {
				assumeTrue(false, "Playwright Firefox not available: " + e.getMessage());
			}
			CrawlBreaker breaker = new CrawlBreaker(() -> 3, () -> Duration.ofHours(6), () -> Duration.ofHours(24),
					Clock.systemUTC());
			String base = "http://127.0.0.1:" + server.getAddress().getPort();
			Future<ScrapeResult> blocked = pool.submit(() -> new FoodstuffsChainScraper(ChainId.NEWWORLD, "http://127.0.0.1")
					.scrape(browsers.forChain(ChainId.NEWWORLD), config(breaker, ChainId.NEWWORLD,
							List.of(base + "/blocked/1?pg=1", base + "/blocked/2?pg=1", base + "/blocked/3?pg=1",
									base + "/blocked/4?pg=1"))));
			Future<ScrapeResult> good = pool.submit(() -> new FoodstuffsChainScraper(ChainId.PAKNSAVE, "http://127.0.0.1")
					.scrape(browsers.forChain(ChainId.PAKNSAVE), config(breaker, ChainId.PAKNSAVE,
							List.of(base + "/shop/category/eggs?pg=1"))));

			assertThat(blocked.get().getCategoriesVisited()).isEmpty();
			assertThat(good.get().getProducts()).hasSize(2);
			assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();
			assertThat(breaker.isOpen(ChainId.PAKNSAVE)).isFalse();
			assertThat(browsers.openCount()).isEqualTo(2);
		} finally {
			pool.shutdownNow();
			browsers.close();
			server.stop(0);
		}
		Thread.sleep(1500);   // let the OS reap the Firefox children
		assertThat(browsers.openCount()).isZero();
		assertThat(firefoxProcesses()).as("no Firefox left behind").isLessThanOrEqualTo(firefoxBefore);
	}

	private static ChainScraperConfig config(CrawlBreaker breaker, ChainId chain, List<String> urls) {
		return ChainScraperConfig.builder().storeId("").storeName("").categoryUrls(urls).maxPagesPerCategory(8)
				.navigationTimeoutSeconds(15).crawlBrake(breaker.forChain(chain)).build();
	}

	private static long firefoxProcesses() {
		return ProcessHandle.current().descendants()
				.filter(p -> p.info().command().map(c -> c.contains("firefox")).orElse(false)).count();
	}
}
