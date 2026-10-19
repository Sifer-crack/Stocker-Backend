package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Request-bound refreshes stop when the request is cancelled and hand back the page budget they did not use. */
class IngestSchedulerCancellationTest {

	private final List<ChainScraperConfig> configs = new CopyOnWriteArrayList<>();
	private volatile Consumer<ChainScraperConfig> duringScrape = config -> { };
	private IngestProperties properties;
	private IngestScheduler scheduler;
	private final ExecutorService pool = Executors.newCachedThreadPool();

	@BeforeEach
	void setUp() {
		properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setMaxPagesPerChainPerDay(5);
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(List.of("u1", "u2", "u3"));
		ChainScraper scraper = new ChainScraper() {
			@Override
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				configs.add(config);
				duringScrape.accept(config);
				List<String> visited = new ArrayList<>();
				for (String url : config.getCategoryUrls()) {
					if (config.getCancellation().isCancelled()) {
						break;
					}
					visited.add(url);
				}
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of()).categoriesVisited(visited)
						.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
		scheduler = new IngestScheduler(List.of(scraper), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class));
	}

	@AfterEach
	void tearDown() {
		pool.shutdownNow();
	}

	@Test
	void aTokenCancelledBeforeStartScrapesNothingAndReservesNothing() {
		CancellationToken token = CancellationToken.create();
		token.cancel();

		int saved = scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b"), token);

		assertThat(saved).isZero();
		assertThat(configs).isEmpty();
		// the whole daily budget is still there: five pages are granted
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c", "d", "e"));
		assertThat(configs.get(0).getCategoryUrls()).hasSize(5);
	}

	@Test
	void theScraperGetsTheRequestsToken() {
		CancellationToken token = CancellationToken.create();

		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a"), token);

		assertThat(configs.get(0).getCancellation()).isSameAs(token);
	}

	@Test
	void pagesNeverLoadedBecauseOfACancelAreGivenBackToTheDailyBudget() {
		CancellationToken token = CancellationToken.create();
		duringScrape = config -> token.cancel();      // cancelled as the scrape begins: loads nothing

		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c", "d"), token);
		duringScrape = config -> { };
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("v", "w", "x", "y", "z"));

		assertThat(configs.get(1).getCategoryUrls()).as("all 5 pages of the budget are still available").hasSize(5);
	}

	@Test
	void midWayCancelChargesOnlyTheVisitedPages() {
		CancellationToken token = CancellationToken.create();
		ScraperProbe probe = new ScraperProbe();
		IngestScheduler midway = probe.scheduler(properties, 2);       // scraper visits 2 pages then sees the cancel

		midway.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c", "d"), token);
		midway.refreshCategories(ChainId.NEWWORLD, List.of("p", "q", "r", "s", "t"));

		assertThat(probe.requested.get(0)).hasSize(4);
		assertThat(probe.requested.get(1)).as("5 cap - 2 visited = 3 left").hasSize(3);
	}

	@Test
	void theScheduledCrawlIsNeverCancellable() {
		scheduler.runAll();

		assertThat(configs).isNotEmpty();
		ChainScraperConfig config = configs.get(0);
		assertThat(config.getCancellation().cancel()).isFalse();
		assertThat(config.getCancellation().isCancelled()).isFalse();
	}

	@Test
	void aRefreshWaitingForTheBrowserGivesUpWhenCancelledAndReturnsItsBudget() throws Exception {
		CountDownLatch firstScraping = new CountDownLatch(1);
		CountDownLatch finishFirst = new CountDownLatch(1);
		duringScrape = config -> {
			if (configs.size() == 1) {
				firstScraping.countDown();
				try {
					finishFirst.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException ignored) {
				}
			}
		};
		Future<Integer> first = pool.submit(() -> scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b")));
		assertThat(firstScraping.await(5, TimeUnit.SECONDS)).isTrue();
		CancellationToken token = CancellationToken.create();
		Future<Integer> waiting = pool.submit(() -> scheduler.refreshCategories(ChainId.NEWWORLD, List.of("c", "d"), token));
		Thread.sleep(200);                               // it is now queued for the browser
		token.cancel();

		assertThat(waiting.get(3, TimeUnit.SECONDS)).isZero();
		assertThat(configs).as("the cancelled refresh never touched the browser").hasSize(1);
		finishFirst.countDown();
		first.get(5, TimeUnit.SECONDS);
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("e", "f", "g"));
		assertThat(configs.get(1).getCategoryUrls()).as("5 - 2 (first) = 3, the cancelled pair came back").hasSize(3);
	}

	/** A scraper that records how many pages it was asked for and visits only {@code visits} before reporting. */
	private static final class ScraperProbe {
		final List<List<String>> requested = new ArrayList<>();

		IngestScheduler scheduler(IngestProperties properties, int visits) {
			ChainScraper scraper = new ChainScraper() {
				@Override
				public ChainId chainId() {
					return ChainId.NEWWORLD;
				}

				@Override
				public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
					requested.add(config.getCategoryUrls());
					List<String> visited = config.getCategoryUrls().subList(0, Math.min(visits, config.getCategoryUrls().size()));
					return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of()).categoriesVisited(visited)
							.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
				}
			};
			return new IngestScheduler(List.of(scraper), mock(Browser.class), properties,
					mock(PriceRecordRepository.class), mock(PriceStatsService.class));
		}
	}
}
