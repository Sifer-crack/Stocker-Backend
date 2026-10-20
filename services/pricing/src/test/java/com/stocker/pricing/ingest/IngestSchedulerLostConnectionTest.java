package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When the database connection is lost, the row-by-row fallback must not wait for a connection once per row, and a
 * category whose rows were not stored must be retried soon instead of being treated as crawled.
 */
class IngestSchedulerLostConnectionTest {

	private static final String C1 = "https://www.newworld.co.nz/shop/category/c1";
	private static final String C2 = "https://www.newworld.co.nz/shop/category/c2";

	private final List<List<String>> plans = new ArrayList<>();
	private PriceRecordRepository repository;
	private PriceStatsService stats;
	private IngestProperties properties;

	@BeforeEach
	void setUp() {
		repository = mock(PriceRecordRepository.class);
		stats = mock(PriceStatsService.class);
		properties = new IngestProperties();
		properties.setDryRun(false);
		properties.setStapleDiscoveryEnabled(false);
		properties.setMaxCategoriesPerRun(0);
		properties.setPersistRetryBackoffMillis(List.of());
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(List.of(C1, C2));
	}

	private static RawProduct product(String code) {
		return RawProduct.builder().chainId("newworld").nativeProductCode(code).name(code).price(BigDecimal.ONE).build();
	}

	private static RuntimeException connectionLost() {
		return new RuntimeException("HikariPool marked as broken", new SQLException("closed", "08006"));
	}

	private IngestScheduler scheduler(Map<String, List<RawProduct>> byCategory) {
		ChainScraper scraper = new ChainScraper() {
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				plans.add(List.copyOf(config.getCategoryUrls()));
				List<RawProduct> all = new ArrayList<>();
				for (String url : config.getCategoryUrls()) {
					List<RawProduct> products = byCategory.getOrDefault(url, List.of());
					all.addAll(products);
					config.getCategoryResultSink().onCategory(url, products);
				}
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(all)
						.categoriesVisited(config.getCategoryUrls()).zeroResultCategoryUrls(List.of()).warnings(List.of())
						.build();
			}
		};
		return new IngestScheduler(List.of(scraper), mock(Browser.class), properties, repository, stats,
				CategoryMap.empty(), Clock.systemUTC());
	}

	@Test
	void aLostConnectionStopsTheRowByRowLoopAndTheRemainingRowsAreNotTried() {
		doAnswer(inv -> {
			throw connectionLost();
		}).when(stats).saveAndRecordAll(anyList());
		doAnswer(inv -> {
			throw connectionLost();
		}).when(repository).save(any(PriceRecord.class));

		scheduler(Map.of(C1, List.of(product("A"), product("B"), product("C"), product("D")))).runAll();

		verify(repository, times(1)).save(any(PriceRecord.class));      // A failed with a lost connection; B, C, D never tried
	}

	@Test
	void anOrdinaryRowErrorStillOnlyCostsThatRow() {
		doAnswer(inv -> {
			throw new IllegalStateException("batch rolled back");
		}).when(stats).saveAndRecordAll(anyList());
		doAnswer(inv -> {
			PriceRecord r = inv.getArgument(0);
			if (r.getItemId().equals("newworld:B")) {
				throw new IllegalStateException("bad row");
			}
			return r;
		}).when(repository).save(any(PriceRecord.class));

		scheduler(Map.of(C1, List.of(product("A"), product("B"), product("C")))).runAll();

		verify(repository, times(3)).save(any(PriceRecord.class));
	}

	@Test
	void aCategoryWhoseRowsWereNotStoredComesRoundAgainFirstNextTime() {
		doAnswer(inv -> {
			List<PriceRecord> rows = inv.getArgument(0);
			if (rows.stream().anyMatch(r -> r.getItemId().equals("newworld:B"))) {
				throw connectionLost();
			}
			return null;
		}).when(stats).saveAndRecordAll(anyList());
		doAnswer(inv -> {
			PriceRecord r = inv.getArgument(0);
			if (r.getItemId().equals("newworld:B")) {
				throw connectionLost();
			}
			return r;
		}).when(repository).save(any(PriceRecord.class));
		IngestScheduler scheduler = scheduler(Map.of(C1, List.of(product("A")), C2, List.of(product("B"))));

		scheduler.runAll();
		scheduler.runAll();

		assertThat(plans.get(0)).containsExactly(C1, C2);
		// C1 was stored, so it counts as freshly crawled and goes to the back; C2 failed and stays at the front
		assertThat(plans.get(1)).containsExactly(C2, C1);
	}
}
