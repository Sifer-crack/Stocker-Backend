package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What a daily run loads, in what order, and how further pages of a category are paid for. */
class IngestSchedulerPlanningTest {

	private static final String NW = "https://www.newworld.co.nz/shop/category/";

	private final List<List<String>> scraped = new ArrayList<>();
	private int extraPagesGranted;
	private int extraPagesWanted;
	private IngestProperties properties;

	@BeforeEach
	void setUp() {
		properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setMaxPagesPerChainPerDay(140);
		properties.getNewworld().setEnabled(true);
	}

	private ChainScraper scraper() {
		return new ChainScraper() {
			@Override
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				scraped.add(config.getCategoryUrls());
				for (int i = 0; i < extraPagesWanted; i++) {
					if (config.getExtraPageGate().getAsBoolean()) {
						extraPagesGranted++;
					}
				}
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of())
						.categoriesVisited(config.getCategoryUrls()).zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
	}

	private IngestScheduler scheduler(CategoryMap map, Function<ChainId, List<String>> discovered) {
		Clock clock = Clock.systemUTC();
		return new IngestScheduler(List.of(scraper()), mock(Browser.class), properties, mock(PriceRecordRepository.class),
				mock(PriceStatsService.class), map, clock, new CategoryHealth(2, Duration.ofDays(7), 0.5, clock), discovered);
	}

	private static CategoryMap map(String... lines) throws Exception {
		return CategoryMap.parse(new BufferedReader(new StringReader(String.join("\n", lines))));
	}

	@Test
	void catalogueAndStaplePagesComeBeforeTheOtherConfiguredPages() throws Exception {
		properties.getNewworld().setCategoryUrls(List.of(NW + "toys/lego", NW + "pantry/rice", NW + "garden/hoses"));

		scheduler(map("milk|dairy|-"), chain -> List.of()).runAll();
		scheduler(map("milk|fridge/milk|-"), chain -> List.of()).runAll();

		assertThat(scraped.get(1)).containsExactly(NW + "fridge/milk?pg=1", NW + "pantry/rice", NW + "toys/lego", NW + "garden/hoses");
	}

	@Test
	void sitemapDiscoveredStaplePagesAreCrawledWithoutBeingCheckedFirst() throws Exception {
		properties.getNewworld().setCategoryUrls(List.of(NW + "toys/lego"));

		scheduler(CategoryMap.empty(), chain -> List.of(NW + "pantry/flour", NW + "toys/lego")).runAll();

		assertThat(scraped.get(0)).containsExactly(NW + "pantry/flour", NW + "toys/lego");
	}

	@Test
	void discoveryCanBeSwitchedOffAndItsFailuresDoNotStopTheCrawl() throws Exception {
		properties.getNewworld().setCategoryUrls(List.of(NW + "toys/lego"));
		properties.setStapleDiscoveryEnabled(false);
		scheduler(CategoryMap.empty(), chain -> List.of(NW + "pantry/flour")).runAll();
		assertThat(scraped.get(0)).containsExactly(NW + "toys/lego");

		properties.setStapleDiscoveryEnabled(true);
		scheduler(CategoryMap.empty(), chain -> {
			throw new IllegalStateException("sitemap down");
		}).runAll();
		assertThat(scraped.get(1)).containsExactly(NW + "toys/lego");
	}

	@Test
	void furtherPagesOfACategoryDrawFromTheSameDailyBudget() throws Exception {
		properties.setMaxPagesPerChainPerDay(10);
		properties.setExtraPagesReservePerRun(0);
		properties.getNewworld().setCategoryUrls(List.of("a", "b", "c", "d"));
		extraPagesWanted = 100;
		IngestScheduler scheduler = scheduler(CategoryMap.empty(), chain -> List.of());

		scheduler.runAll();

		assertThat(scraped.get(0)).hasSize(4);
		assertThat(extraPagesGranted).isEqualTo(6);            // 10 - 4 first pages
		assertThat(scheduler.refreshCategories(ChainId.NEWWORLD, List.of("x"))).isZero();
		assertThat(scraped).hasSize(1);                          // budget is gone: nothing else is loaded today
	}

	@Test
	void aLongListLeavesRoomForFurtherPages() throws Exception {
		properties.setMaxPagesPerChainPerDay(100);
		properties.setExtraPagesReservePerRun(15);
		List<String> urls = new ArrayList<>();
		for (int i = 0; i < 120; i++) {
			urls.add("u" + i);
		}
		properties.getNewworld().setCategoryUrls(urls);
		extraPagesWanted = 1000;

		scheduler(CategoryMap.empty(), chain -> List.of()).runAll();

		assertThat(scraped.get(0)).hasSize(85);
		assertThat(extraPagesGranted).isEqualTo(15);
	}

	@Test
	void recentlyCrawledPagesMoveToTheBack() throws Exception {
		properties.getNewworld().setCategoryUrls(List.of(NW + "a/rice", NW + "a/flour", NW + "toys/lego"));
		IngestScheduler scheduler = scheduler(CategoryMap.empty(), chain -> List.of());
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of(NW + "a/rice"));

		scheduler.runAll();

		assertThat(scraped.get(1)).containsExactly(NW + "a/flour", NW + "toys/lego", NW + "a/rice");
	}
}
