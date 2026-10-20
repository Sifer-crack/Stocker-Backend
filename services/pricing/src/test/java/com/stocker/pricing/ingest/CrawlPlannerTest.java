package com.stocker.pricing.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlPlannerTest {

	private static final Instant NOW = Instant.parse("2026-10-20T14:00:00Z");
	private static final Duration FRESH = Duration.ofHours(20);

	@Test
	void everythingFitsKeepsPriorityBeforeTheRest() {
		var plan = CrawlPlanner.plan(List.of("milk", "eggs"), List.of("a", "b"), Map.of(), FRESH, NOW, 10, 0);

		assertThat(plan).containsExactly("milk", "eggs", "a", "b");
	}

	@Test
	void underATightBudgetPriorityPagesAreAlwaysTakenAndTheRestRotates() {
		List<String> others = List.of("a", "b", "c", "d", "e", "f");

		var day0 = CrawlPlanner.plan(List.of("milk", "eggs"), others, Map.of(), FRESH, NOW, 4, 0);
		var day1 = CrawlPlanner.plan(List.of("milk", "eggs"), others, Map.of(), FRESH, NOW, 4, 1);
		var day2 = CrawlPlanner.plan(List.of("milk", "eggs"), others, Map.of(), FRESH, NOW, 4, 2);

		assertThat(day0).startsWith("milk", "eggs").hasSize(4);
		assertThat(day1).startsWith("milk", "eggs").hasSize(4);
		assertThat(day0.subList(2, 4)).isNotEqualTo(day1.subList(2, 4));
		assertThat(List.of(day0, day1, day2).stream().flatMap(List::stream).distinct().toList()).containsAll(others);
	}

	@Test
	void recentlyCrawledPagesGoToTheBackAndAreCutFirst() {
		var lastCrawled = Map.of("milk", NOW.minus(Duration.ofHours(2)), "a", NOW.minus(Duration.ofHours(1)));

		var plan = CrawlPlanner.plan(List.of("milk", "eggs"), List.of("a", "b"), lastCrawled, FRESH, NOW, 10, 0);

		assertThat(plan).containsExactly("eggs", "b", "milk", "a");
		assertThat(CrawlPlanner.plan(List.of("milk", "eggs"), List.of("a", "b"), lastCrawled, FRESH, NOW, 3, 0))
				.containsExactly("eggs", "b", "milk");
	}

	@Test
	void aPageCrawledLongAgoIsStaleAgain() {
		var lastCrawled = Map.of("milk", NOW.minus(Duration.ofHours(25)));

		var plan = CrawlPlanner.plan(List.of("milk", "eggs"), List.of(), lastCrawled, FRESH, NOW, 10, 0);

		assertThat(plan).containsExactly("milk", "eggs");
	}

	@Test
	void morePriorityPagesThanTheWindowRotateSoNoneStarves() {
		List<String> priority = List.of("p1", "p2", "p3", "p4", "p5");

		var day0 = CrawlPlanner.plan(priority, List.of("a"), Map.of(), FRESH, NOW, 2, 0);
		var day1 = CrawlPlanner.plan(priority, List.of("a"), Map.of(), FRESH, NOW, 2, 1);
		var day2 = CrawlPlanner.plan(priority, List.of("a"), Map.of(), FRESH, NOW, 2, 2);

		assertThat(day0).hasSize(2).isSubsetOf(priority);
		assertThat(List.of(day0, day1, day2).stream().flatMap(List::stream).distinct().toList()).containsAll(priority);
	}

	@Test
	void aPageListedAsBothPriorityAndOtherIsLoadedOnce() {
		var plan = CrawlPlanner.plan(List.of("milk"), List.of("milk", "a"), Map.of(), FRESH, NOW, 10, 0);

		assertThat(plan).containsExactly("milk", "a");
	}
}
