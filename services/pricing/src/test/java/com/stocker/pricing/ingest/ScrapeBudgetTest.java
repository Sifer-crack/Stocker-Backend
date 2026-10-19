package com.stocker.pricing.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stocker.pricing.ingest.model.ChainScraperConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScrapeBudgetTest {

	@Test
	void pacerDoesNothingWhenDelaysAreZeroAndWaitsAtLeastTheMinimum() {
		long start = System.nanoTime();
		ScrapePacer.pauseBetweenPages(ChainScraperConfig.builder().build());
		assertTrue((System.nanoTime() - start) / 1_000_000 < 50);

		start = System.nanoTime();
		ScrapePacer.pauseBetweenPages(ChainScraperConfig.builder().pageDelayMinMillis(60).pageDelayMaxMillis(80).build());
		assertTrue((System.nanoTime() - start) / 1_000_000 >= 55);
	}

	@Test
	void categoryBudgetRotatesThroughTheListAcrossDays() {
		List<String> urls = List.of("a", "b", "c", "d", "e");
		assertEquals(urls, IngestScheduler.selectCategories(urls, 12, 1));
		assertEquals(List.of("c", "d"), IngestScheduler.selectCategories(urls, 2, 1));
		assertEquals(List.of("e", "a"), IngestScheduler.selectCategories(urls, 2, 2));
	}
}
