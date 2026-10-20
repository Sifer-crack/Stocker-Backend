package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Rows are stored after every category, not when the chain's run ends. */
class IngestSchedulerIncrementalSaveTest {

	private final List<String> savedItemIds = new CopyOnWriteArrayList<>();
	private final List<String> statsItemIds = new CopyOnWriteArrayList<>();
	private PriceRecordRepository repository;
	private PriceStatsService stats;
	private IngestProperties properties;

	@BeforeEach
	void setUp() {
		repository = mock(PriceRecordRepository.class);
		stats = mock(PriceStatsService.class);
		doAnswer(inv -> {
			PriceRecord r = inv.getArgument(0);
			savedItemIds.add(r.getItemId());
			return r;
		}).when(repository).save(any(PriceRecord.class));
		doAnswer(inv -> {
			statsItemIds.add(((PriceRecord) inv.getArgument(0)).getItemId());
			return null;
		}).when(stats).recordObservation(any(PriceRecord.class));
		properties = new IngestProperties();
		properties.setDryRun(false);
		properties.setStapleDiscoveryEnabled(false);
		properties.setMaxCategoriesPerRun(0);
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(List.of("c1", "c2", "c3"));
	}

	private static RawProduct product(String code) {
		return RawProduct.builder().chainId("newworld").nativeProductCode(code).name(code)
				.price(BigDecimal.ONE).build();
	}

	/** Scraper emitting the given products per category; {@code afterCategory} runs right after each sink call. */
	private ChainScraper scraper(Map<String, List<RawProduct>> byCategory, boolean callSink,
			BiConsumer<Integer, ChainScraperConfig> afterCategory) {
		return new ChainScraper() {
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				List<RawProduct> all = new ArrayList<>();
				List<String> visited = new ArrayList<>();
				int n = 0;
				for (String url : config.getCategoryUrls()) {
					List<RawProduct> products = byCategory.getOrDefault(url, List.of());
					all.addAll(products);
					visited.add(url);
					if (callSink) {
						config.getCategoryResultSink().onCategory(url, products);
					}
					afterCategory.accept(++n, config);
				}
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(all).categoriesVisited(visited)
						.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
	}

	private IngestScheduler scheduler(ChainScraper scraper) {
		return new IngestScheduler(List.of(scraper), mock(Browser.class), properties, repository, stats,
				CategoryMap.empty(), Clock.systemUTC());
	}

	@Test
	void rowsOfFinishedCategoriesAreStoredWhileTheRunIsStillGoing() {
		List<List<String>> snapshots = new ArrayList<>();
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A")), "c2", List.of(product("B")),
				"c3", List.of(product("C"))), true, (n, cfg) -> snapshots.add(List.copyOf(savedItemIds)));

		scheduler(scraper).runAll();

		assertThat(snapshots).containsExactly(List.of("newworld:A"), List.of("newworld:A", "newworld:B"),
				List.of("newworld:A", "newworld:B", "newworld:C"));
		assertThat(statsItemIds).containsExactlyElementsOf(savedItemIds);
	}

	@Test
	void anExceptionInTheThirdCategoryKeepsTheFirstTwoStored() {
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A")), "c2", List.of(product("B"))), true,
				(n, cfg) -> {
					if (n == 2) {
						throw new IllegalStateException("boom in category 3");
					}
				});

		try {
			scheduler(scraper).runAll();
		} catch (IllegalStateException expected) {
			// the run dies, what was stored stays
		}

		assertThat(savedItemIds).containsExactly("newworld:A", "newworld:B");
	}

	@Test
	void aBreakerTripAfterTwoCategoriesKeepsThemStored() {
		properties.setBreakerBlockedThreshold(1);
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A")), "c2", List.of(product("B"))), true,
				(n, cfg) -> {
					if (n == 2) {
						cfg.getCrawlBrake().onBlocked("HTTP 429", null);
					}
				});
		IngestScheduler scheduler = scheduler(scraper);

		scheduler.runAll();

		assertThat(savedItemIds).containsExactly("newworld:A", "newworld:B");
		assertThat(scheduler.isChainBlocked(ChainId.NEWWORLD)).isTrue();
	}

	@Test
	void aProductInTwoCategoriesIsStoredOnce() {
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A"), product("B")),
				"c2", List.of(product("B"), product("C")), "c3", List.of(product("A"))), true, (n, cfg) -> { });

		scheduler(scraper).runAll();

		assertThat(savedItemIds).containsExactly("newworld:A", "newworld:B", "newworld:C");
	}

	@Test
	void aStoringFailureInOneCategoryDoesNotStopTheOthers() {
		doAnswer(inv -> {
			PriceRecord r = inv.getArgument(0);
			if (r.getItemId().equals("newworld:B")) {
				throw new IllegalStateException("db down");
			}
			savedItemIds.add(r.getItemId());
			return r;
		}).when(repository).save(any(PriceRecord.class));
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A")), "c2", List.of(product("B")),
				"c3", List.of(product("C"))), true, (n, cfg) -> { });

		scheduler(scraper).runAll();

		assertThat(savedItemIds).containsExactly("newworld:A", "newworld:C");
	}

	@Test
	void aScraperThatNeverReportsCategoriesIsStoredAtTheEndOfTheRunOnce() {
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A"), product("B")),
				"c2", List.of(product("B"))), false, (n, cfg) -> assertThat(savedItemIds).isEmpty());

		scheduler(scraper).runAll();

		assertThat(savedItemIds).containsExactly("newworld:A", "newworld:B");
	}

	@Test
	void dryRunStoresNothing() {
		properties.setDryRun(true);
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A"))), true, (n, cfg) -> { });

		scheduler(scraper).runAll();

		assertThat(savedItemIds).isEmpty();
	}

	@Test
	void parallelModeStoresEachChainsCategoriesIncrementally() {
		properties.setParallelChains(true);
		properties.getNewworld().setCategoryUrls(List.of("c1", "c2"));
		List<List<String>> snapshots = new CopyOnWriteArrayList<>();
		ChainScraper scraper = scraper(Map.of("c1", List.of(product("A")), "c2", List.of(product("B"))), true,
				(n, cfg) -> snapshots.add(List.copyOf(savedItemIds)));
		IngestScheduler scheduler = new IngestScheduler(List.of(scraper), ChainBrowsers.shared(mock(Browser.class)),
				properties, repository, stats, CategoryMap.empty(), Clock.systemUTC());

		scheduler.runAll();

		assertThat(snapshots).containsExactly(List.of("newworld:A"), List.of("newworld:A", "newworld:B"));
	}
}
