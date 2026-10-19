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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the daily crawl visits: every page the catalog map lists plus any extra configured page (no duplicates),
 * and, when the daily cap is smaller than that list, a window that moves on each day so every page is refreshed
 * within a few days.
 */
class IngestSchedulerCatalogCoverageTest {

	private static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-10-19T14:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final List<List<String>> scraped = new ArrayList<>();
	private final MutableClock clock = new MutableClock();
	private IngestProperties properties;

	private IngestScheduler scheduler(String mapText) throws Exception {
		properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setMaxPagesPerChainPerDay(80);
		properties.getNewworld().setEnabled(true);
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
		return new IngestScheduler(List.of(scraper), mock(Browser.class), properties, mock(PriceRecordRepository.class),
				mock(PriceStatsService.class), CategoryMap.parse(new BufferedReader(new StringReader(mapText))), clock);
	}

	private static final String BASE = "https://www.newworld.co.nz/shop/category/";

	@BeforeEach
	void reset() {
		scraped.clear();
	}

	@Test
	void theCrawlVisitsTheCatalogPagesAndTheConfiguredExtrasWithoutDuplicates() throws Exception {
		IngestScheduler scheduler = scheduler("milk|dairy/milk|-\neggs|dairy/eggs|-\n");
		properties.getNewworld().setCategoryUrls(List.of(BASE + "dairy/milk?pg=1", BASE + "pantry/extra?pg=1"));

		scheduler.runAll();

		assertThat(scraped).hasSize(1);
		assertThat(scraped.get(0)).containsExactlyInAnyOrder(BASE + "dairy/milk?pg=1", BASE + "pantry/extra?pg=1", BASE + "dairy/eggs?pg=1");
	}

	@Test
	void whenTheCapIsSmallerThanTheListTheWindowMovesOnEachDay() throws Exception {
		StringBuilder map = new StringBuilder();
		for (int i = 0; i < 8; i++) {
			map.append("p").append(i).append("|cat/p").append(i).append("|-\n");
		}
		IngestScheduler scheduler = scheduler(map.toString());
		properties.setMaxPagesPerChainPerDay(3);
		Set<String> visited = new HashSet<>();

		for (int day = 0; day < 3; day++) {
			clock.now = Instant.parse("2026-10-19T14:00:00Z").plusSeconds(86_400L * day);
			scheduler.runAll();
			assertThat(scraped.get(day)).hasSize(3);
			visited.addAll(scraped.get(day));
		}

		assertThat(visited).hasSize(8);     // all eight pages were refreshed within three days
	}

	@Test
	void aSmallListFitsInOneDayAndIsVisitedWhole() throws Exception {
		IngestScheduler scheduler = scheduler("a|cat/a|-\nb|cat/b|-\n");

		scheduler.runAll();

		assertThat(scraped.get(0)).hasSize(2);
	}
}
