package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A page that keeps returning zero products is skipped and costs no page budget; it is retried after the period. */
class IngestSchedulerQuarantineTest {

	private static final String EMPTY = "empty";

	private final List<List<String>> scraped = new ArrayList<>();
	private MutableClock clock;
	private IngestScheduler scheduler;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
		IngestProperties properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setMaxPagesPerChainPerDay(4);       // exactly the four configured pages per day
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(List.of(EMPTY, "u2", "u3", "u4"));
		ChainScraper scraper = new ChainScraper() {
			@Override
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				scraped.add(config.getCategoryUrls());
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of())
						.categoriesVisited(config.getCategoryUrls())
						.zeroResultCategoryUrls(config.getCategoryUrls().stream().filter(EMPTY::equals).toList())
						.warnings(List.of()).build();
			}
		};
		CategoryHealth health = new CategoryHealth(2, Duration.ofDays(7), clock);
		scheduler = new IngestScheduler(List.of(scraper), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class), CategoryMap.empty(), clock, health);
	}

	private void nextDay() {
		clock.advance(Duration.ofDays(1));
	}

	private void quarantineTheEmptyPage() {
		scheduler.runAll();     // zero #1
		nextDay();
		scheduler.runAll();     // zero #2 -> quarantined
		nextDay();
		scraped.clear();
	}

	@Test
	void quarantinedPageIsNotScrapedAndNotChargedToTheBudget() {
		quarantineTheEmptyPage();

		scheduler.runAll();
		assertThat(scraped).hasSize(1);
		assertThat(scraped.get(0)).containsExactly("u2", "u3", "u4");

		// the cap is 4 and only 3 pages were loaded today, so one more page still fits
		assertThat(scheduler.refreshCategories(ChainId.NEWWORLD, List.of("extra"))).isZero();   // dry-run saves 0
		assertThat(scraped).hasSize(2);
		assertThat(scraped.get(1)).containsExactly("extra");
	}

	@Test
	void pageIsRetriedAfterThePeriodAndRequarantinedIfStillEmpty() {
		quarantineTheEmptyPage();
		clock.advance(Duration.ofDays(7));

		scheduler.runAll();
		assertThat(scraped.get(0)).contains(EMPTY);      // the single retry

		nextDay();
		scraped.clear();
		scheduler.runAll();
		assertThat(scraped.get(0)).doesNotContain(EMPTY);   // zero again -> straight back into quarantine
	}

	@Test
	void onDemandRefreshSkipsAQuarantinedPage() {
		quarantineTheEmptyPage();

		scheduler.refreshCategories(ChainId.NEWWORLD, List.of(EMPTY, "u2"));
		assertThat(scraped).hasSize(1);
		assertThat(scraped.get(0)).containsExactly("u2");

		scraped.clear();
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of(EMPTY));
		assertThat(scraped).isEmpty();      // nothing left to load, no scrape at all
	}

	@Test
	void beforeTheThresholdThePageIsStillCrawled() {
		scheduler.runAll();
		nextDay();
		scraped.clear();
		scheduler.runAll();

		assertThat(scraped.get(0)).contains(EMPTY);
	}

	@Test
	void aSiteWideZeroRunNeverQuarantinesAnything() {
		// every page empty on every run: 4 of 4 zero trips the guard
		IngestProperties properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setMaxPagesPerChainPerDay(4);
		properties.getNewworld().setEnabled(true);
		properties.getNewworld().setCategoryUrls(List.of("a", "b", "c", "d"));
		List<List<String>> seen = new ArrayList<>();
		ChainScraper allEmpty = new ChainScraper() {
			@Override
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				seen.add(config.getCategoryUrls());
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of())
						.categoriesVisited(config.getCategoryUrls()).zeroResultCategoryUrls(config.getCategoryUrls())
						.warnings(List.of()).build();
			}
		};
		CategoryHealth health = new CategoryHealth(2, Duration.ofDays(7), 0.5, clock);
		IngestScheduler s = new IngestScheduler(List.of(allEmpty), mock(Browser.class), properties,
				mock(PriceRecordRepository.class), mock(PriceStatsService.class), CategoryMap.empty(), clock, health);

		for (int day = 0; day < 4; day++) {
			s.runAll();
			nextDay();
		}

		assertThat(seen).hasSize(4);
		assertThat(seen).allSatisfy(pages -> assertThat(pages).hasSize(4));
		assertThat(health.snapshot()).isEmpty();
	}
}
