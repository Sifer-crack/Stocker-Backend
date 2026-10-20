package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The daily page budget is charged for the page requests that were really sent: a scrape that dies before it loads
 * anything (dead browser, failed context) must not burn the day, and a first page that failed (timeout / error
 * status / block) must not be refunded as if it had never been requested.
 */
class IngestSchedulerBudgetRefundTest {

	private static final String BASE = "https://www.newworld.co.nz/shop/category/";

	private final List<ChainScraperConfig> configs = new ArrayList<>();
	private IngestProperties properties;
	private Function<ChainScraperConfig, ScrapeResult> behaviour;
	private IngestScheduler scheduler;

	@BeforeEach
	void setUp() {
		properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setStapleDiscoveryEnabled(false);
		properties.setMaxPagesPerChainPerDay(10);
		properties.getNewworld().setEnabled(true);
		ChainScraper scraper = new ChainScraper() {
			@Override
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				configs.add(config);
				return behaviour.apply(config);
			}
		};
		scheduler = new IngestScheduler(List.of(scraper), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class), CategoryMap.empty(), Clock.systemUTC());
	}

	private static List<String> urls(int n) {
		List<String> urls = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			urls.add(BASE + "c" + i);
		}
		return urls;
	}

	private static ScrapeResult visited(ChainScraperConfig config, List<String> visited) {
		return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of()).categoriesVisited(visited)
				.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
	}

	@Test
	void aScrapeThatThrowsBeforeLoadingAnythingGivesTheWholeReservationBack() {
		behaviour = config -> {
			throw new IllegalStateException("browser died at newContext");
		};

		assertThatThrownBy(() -> scheduler.refreshCategories(ChainId.NEWWORLD, urls(10))).isInstanceOf(IllegalStateException.class);
		behaviour = config -> visited(config, config.getCategoryUrls());
		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));

		assertThat(configs.get(1).getCategoryUrls()).hasSize(10);       // the full day is still there
	}

	@Test
	void aScrapeExceptionAlsoGivesTheUnrequestedPagesBack() {
		behaviour = config -> {
			throw new ScrapeException("firefox crashed", new RuntimeException());
		};

		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));
		behaviour = config -> visited(config, config.getCategoryUrls());
		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));

		assertThat(configs.get(1).getCategoryUrls()).hasSize(10);
	}

	@Test
	void pagesRequestedBeforeTheScrapeThrewStayCharged() {
		behaviour = config -> {
			config.getCrawlBrake().onPageRequested();
			config.getCrawlBrake().onSuccess();
			config.getCrawlBrake().onPageRequested();
			config.getCrawlBrake().onBlocked("HTTP 429", null);
			throw new IllegalStateException("context closed");
		};

		assertThatThrownBy(() -> scheduler.refreshCategories(ChainId.NEWWORLD, urls(10))).isInstanceOf(IllegalStateException.class);
		behaviour = config -> visited(config, config.getCategoryUrls());
		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));

		assertThat(configs.get(1).getCategoryUrls()).hasSize(8);        // 10 - 2 requests really sent
	}

	@Test
	void aFirstPageThatFailedIsNotRefundedAsIfItHadNeverBeenRequested() {
		behaviour = config -> {
			config.getCrawlBrake().onPageRequested();       // timed out: requested, but not in categoriesVisited
			return visited(config, List.of());
		};

		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));
		behaviour = config -> visited(config, config.getCategoryUrls());
		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));

		assertThat(configs.get(1).getCategoryUrls()).hasSize(9);
	}

	@Test
	void pagesNeverRequestedAreStillRefundedOnANormalReturn() {
		behaviour = config -> {
			config.getCrawlBrake().onPageRequested();
			return visited(config, config.getCategoryUrls().subList(0, 1));
		};

		scheduler.refreshCategories(ChainId.NEWWORLD, urls(6));
		behaviour = config -> visited(config, config.getCategoryUrls());
		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10));

		assertThat(configs.get(1).getCategoryUrls()).hasSize(9);        // 10 - 1 request
	}

	@Test
	void onDemandRefreshesLoadAtMostTheConfiguredPagesPerCategoryAndLeaveTheCrawlItsShare() {
		properties.setMaxPagesPerCategory(8);
		scheduler.limitOnDemand(2, 0.3);                 // 30% of 10 = 3 pages stay for the scheduled crawl
		behaviour = config -> visited(config, config.getCategoryUrls());

		scheduler.refreshCategories(ChainId.NEWWORLD, urls(10), CancellationToken.NONE);

		assertThat(configs.get(0).getMaxPagesPerCategory()).isEqualTo(2);
		assertThat(configs.get(0).getCategoryUrls()).hasSize(7);
		// a second refresh finds only the protected share left
		assertThat(scheduler.refreshCategories(ChainId.NEWWORLD, urls(1), CancellationToken.NONE)).isZero();
		assertThat(configs).hasSize(1);
	}

	@Test
	void theExtraPageGateOfAnOnDemandRefreshStopsAtTheProtectedShare() {
		scheduler.limitOnDemand(2, 0.3);
		AtomicInteger gateGrants = new AtomicInteger();
		behaviour = config -> {
			while (config.getExtraPageGate().getAsBoolean()) {
				gateGrants.incrementAndGet();
			}
			return visited(config, config.getCategoryUrls());
		};

		scheduler.refreshCategories(ChainId.NEWWORLD, urls(2), CancellationToken.NONE);

		// 10 cap - 3 kept = 7 usable: 2 first pages, then 5 further pages, then the gate says no
		assertThat(gateGrants.get()).isEqualTo(5);
	}

	@Test
	void theScheduledCrawlIgnoresTheOnDemandLimits() {
		properties.setMaxPagesPerChainPerDay(100);
		scheduler.limitOnDemand(1, 0.9);
		properties.getNewworld().setCategoryUrls(urls(10));
		properties.setMaxCategoriesPerRun(0);
		behaviour = config -> visited(config, config.getCategoryUrls());

		scheduler.runAll();

		assertThat(configs.get(0).getCategoryUrls()).hasSize(10);
		assertThat(configs.get(0).getMaxPagesPerCategory()).isEqualTo(properties.getMaxPagesPerCategory());
	}

	@Test
	void aChainThatThrowsDoesNotStopTheNextChainInTheSequentialRun() {
		List<ChainId> scraped = new ArrayList<>();
		ChainScraper failing = new ChainScraper() {
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				throw new IllegalStateException("boom");
			}
		};
		ChainScraper healthy = new ChainScraper() {
			public ChainId chainId() {
				return ChainId.PAKNSAVE;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				scraped.add(ChainId.PAKNSAVE);
				return ScrapeResult.builder().chainId(ChainId.PAKNSAVE).products(List.of()).categoriesVisited(List.of())
						.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
		properties.setParallelChains(false);
		properties.getNewworld().setCategoryUrls(urls(1));
		properties.getPaknsave().setEnabled(true);
		properties.getPaknsave().setCategoryUrls(List.of("https://www.paknsave.co.nz/shop/category/x"));
		IngestScheduler sequential = new IngestScheduler(List.of(failing, healthy), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class), CategoryMap.empty(), Clock.systemUTC());

		sequential.runAll();

		assertThat(scraped).containsExactly(ChainId.PAKNSAVE);
	}

	@Test
	void urlsOutsideTheChainsOwnDomainAreNeverCrawledOnceTheRestrictionIsOn() {
		scheduler.restrictToChainDomains();
		properties.getNewworld().setCategoryUrls(List.of(BASE + "milk", "http://www.newworld.co.nz/insecure",
				"https://newworld.co.nz.evil.example/shop", "https://www.paknsave.co.nz/other-chain", "u1"));
		properties.setMaxCategoriesPerRun(0);
		behaviour = config -> visited(config, config.getCategoryUrls());

		scheduler.runAll();
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("https://evil.example/x", BASE + "eggs"));

		assertThat(configs).hasSize(2);
		assertThat(configs.get(0).getCategoryUrls()).containsExactly(BASE + "milk");
		assertThat(configs.get(1).getCategoryUrls()).containsExactly(BASE + "eggs");
	}

	@Test
	void sitemapDiscoveredUrlsAreCheckedToo() {
		properties.setStapleDiscoveryEnabled(true);
		IngestScheduler withDiscovery = new IngestScheduler(List.of(new ChainScraper() {
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				configs.add(config);
				return visited(config, config.getCategoryUrls());
			}
		}), mock(Browser.class), properties, mock(PriceRecordRepository.class), mock(PriceStatsService.class),
				CategoryMap.empty(), Clock.systemUTC(), new CategoryHealth(2, java.time.Duration.ofDays(7), Clock.systemUTC()),
				chain -> List.of("https://www.newworld.co.nz/shop/category/milk", "https://attacker.example/milk"))
				.restrictToChainDomains();

		withDiscovery.runAll();

		assertThat(configs.get(0).getCategoryUrls()).containsExactly("https://www.newworld.co.nz/shop/category/milk");
	}
}
