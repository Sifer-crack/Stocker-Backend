package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** However many pages are asked for (scheduled run or on-demand), no more than the daily cap are ever loaded. */
class IngestSchedulerDailyCapTest {

	private final List<List<String>> scraped = new ArrayList<>();
	private IngestProperties properties;
	private IngestScheduler scheduler;

	@BeforeEach
	void setUp() {
		properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setMaxPagesPerChainPerDay(5);
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(List.of("u1", "u2", "u3", "u4", "u5", "u6", "u7", "u8"));
		ChainScraper scraper = new ChainScraper() {
			@Override
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				scraped.add(config.getCategoryUrls());
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of())
						.categoriesVisited(config.getCategoryUrls()).zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
		scheduler = new IngestScheduler(List.of(scraper), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class));
	}

	@Test
	void aRunAskingForMoreThanTheCapIsTruncated() {
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c", "d", "e", "f", "g"));

		assertThat(scraped).hasSize(1);
		assertThat(scraped.get(0)).containsExactly("a", "b", "c", "d", "e");
	}

	@Test
	void onceTheCapIsReachedNothingIsScrapedAtAll() {
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c", "d", "e"));
		int more = scheduler.refreshCategories(ChainId.NEWWORLD, List.of("x"));

		assertThat(more).isZero();
		assertThat(scraped).hasSize(1);      // the second request never reached the scraper
	}

	@Test
	void theScheduledRunAndOnDemandRefreshesShareTheSameBudget() {
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("a", "b", "c"));   // on-demand uses 3
		scheduler.runAll();                                                       // the full run only gets the 2 left

		assertThat(scraped).hasSize(2);
		assertThat(scraped.get(1)).hasSize(2);
	}

	@Test
	void aFullDailyRunCoversEveryConfiguredCategoryWhenTheCapAllowsIt() {
		properties.setMaxPagesPerChainPerDay(80);
		properties.setMaxCategoriesPerRun(0);          // 0 = every configured category

		scheduler.runAll();

		assertThat(scraped).hasSize(1);
		assertThat(scraped.get(0)).hasSize(8);
	}
}
