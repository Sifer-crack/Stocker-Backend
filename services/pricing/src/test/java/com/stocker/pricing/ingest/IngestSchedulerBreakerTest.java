package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The scheduler wires the breaker into every kind of crawl (cron, startup, on-demand): once a chain's breaker is
 * open the scraper is not even called, other chains are untouched, and the chain comes back after the cool-down.
 */
class IngestSchedulerBreakerTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T00:00:00Z"));
	private final AtomicInteger pagesRequested = new AtomicInteger();
	private final AtomicInteger scrapeCalls = new AtomicInteger();
	private final List<ChainId> scrapedChains = new ArrayList<>();
	private IngestProperties properties;
	private IngestScheduler scheduler;
	private volatile boolean blockEverything = true;

	/** A scraper that follows the contract: asks the brake before each page, reports every response to it. */
	private ChainScraper scraper(ChainId chain) {
		return new ChainScraper() {
			@Override
			public ChainId chainId() {
				return chain;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				scrapeCalls.incrementAndGet();
				scrapedChains.add(chain);
				List<String> visited = new ArrayList<>();
				for (String url : config.getCategoryUrls()) {
					if (config.getCrawlBrake().isOpen()) {
						break;
					}
					pagesRequested.incrementAndGet();
					if (blockEverything) {
						config.getCrawlBrake().onBlocked("HTTP 429", null);
					} else {
						config.getCrawlBrake().onSuccess();
						visited.add(url);
					}
				}
				return ScrapeResult.builder().chainId(chain).products(List.of()).categoriesVisited(visited)
						.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
	}

	@BeforeEach
	void setUp() {
		properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setStapleDiscoveryEnabled(false);
		List<String> urls = List.of("u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8", "u9", "u10");
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(urls);
		properties.getPaknsave().setEnabled(true);
		properties.getPaknsave().setCategoryUrls(urls);
		scheduler = new IngestScheduler(List.of(scraper(ChainId.NEWWORLD), scraper(ChainId.PAKNSAVE)),
				mock(Browser.class), properties, mock(PriceRecordRepository.class), mock(PriceStatsService.class),
				com.stocker.pricing.refresh.CategoryMap.empty(), clock);
	}

	@Test
	void aCronRunStopsAChainAfterThreeBlockedPagesAndLoadsNothingMoreOfIt() {
		scheduler.runAll();

		// 3 pages for New World, 3 for PAK'nSAVE (each chain trips on its own), never the remaining 7
		assertThat(pagesRequested.get()).isEqualTo(6);
		assertThat(scheduler.isChainBlocked(ChainId.NEWWORLD)).isTrue();
		assertThat(scheduler.isChainBlocked(ChainId.PAKNSAVE)).isTrue();
		assertThat(scheduler.isChainBlocked(ChainId.WOOLWORTHS)).isFalse();
	}

	@Test
	void whileOpenNeitherTheCronNorAnOnDemandRefreshReachesTheScraper() {
		scheduler.runAll();
		int calls = scrapeCalls.get();

		scheduler.runAll();
		int saved = scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a"));

		assertThat(saved).isZero();
		assertThat(scrapeCalls.get()).isEqualTo(calls);
	}

	@Test
	void anOpenChainDoesNotStopTheOthers() {
		properties.getPaknsave().setEnabled(false);
		scheduler.runAll();
		properties.getPaknsave().setEnabled(true);
		blockEverything = false;
		scrapedChains.clear();

		scheduler.refreshCategories(ChainId.PAKNSAVE, List.of("a", "b"));

		assertThat(scrapedChains).containsExactly(ChainId.PAKNSAVE);
		assertThat(scheduler.isChainBlocked(ChainId.NEWWORLD)).isTrue();
	}

	@Test
	void afterTheCoolDownTheChainIsCrawledAgain() {
		scheduler.runAll();
		clock.advance(Duration.ofHours(6));
		blockEverything = false;
		int calls = scrapeCalls.get();

		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a"));

		assertThat(scheduler.isChainBlocked(ChainId.NEWWORLD)).isFalse();
		assertThat(scrapeCalls.get()).isEqualTo(calls + 1);
	}

	@Test
	void theBreakerSettingsComeFromTheProperties() {
		properties.setBreakerBlockedThreshold(1);
		IngestScheduler strict = new IngestScheduler(List.of(scraper(ChainId.NEWWORLD)), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class),
				com.stocker.pricing.refresh.CategoryMap.empty(), clock);
		pagesRequested.set(0);

		strict.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c"));

		assertThat(pagesRequested.get()).isEqualTo(1);
		assertThat(strict.isChainBlocked(ChainId.NEWWORLD)).isTrue();
	}
}
