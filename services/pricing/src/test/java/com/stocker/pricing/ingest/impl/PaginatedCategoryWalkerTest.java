package com.stocker.pricing.ingest.impl;

import com.stocker.pricing.ingest.impl.PaginatedCategoryWalker.LoadedPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaginatedCategoryWalkerTest {

	private static final String BASE = "https://x/shop/category/milk?pg=1";

	private final List<String> loaded = new ArrayList<>();
	private final AtomicInteger granted = new AtomicInteger(1000);
	private final AtomicInteger pauses = new AtomicInteger();

	private PaginatedCategoryWalker<Integer> walker(int maxPages) {
		return new PaginatedCategoryWalker<>(maxPages, () -> granted.getAndDecrement() > 0, pauses::incrementAndGet, () -> false);
	}

	private static LoadedPage<Integer> page(int from, int to, int total) {
		return new LoadedPage<>(IntStream.rangeClosed(from, to).boxed().toList(),
				"Showing " + from + " - " + to + " of " + total + " products");
	}

	private PaginatedCategoryWalker.Loader<Integer> loader(Map<String, LoadedPage<Integer>> pages) {
		return url -> {
			loaded.add(url);
			LoadedPage<Integer> p = pages.get(url);
			if (p == null) {
				throw new IllegalStateException("blocked " + url);
			}
			return p;
		};
	}

	@Test
	void aSinglePageCategoryCostsExactlyOnePage() {
		var walk = walker(8).walk(BASE, loader(Map.of(BASE, page(1, 33, 33))));

		assertThat(walk.items()).hasSize(33);
		assertThat(walk.pagesLoaded()).isEqualTo(1);
		assertThat(loaded).containsExactly(BASE);
		assertThat(granted.get()).isEqualTo(1000);      // no extra page was even asked for
		assertThat(pauses.get()).isZero();
		assertThat(walk.warning()).isNull();
	}

	@Test
	void followsEveryPageUntilAllProductsAreCollected() {
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 48, 100),
				"https://x/shop/category/milk?pg=2", page(49, 96, 100),
				"https://x/shop/category/milk?pg=3", page(97, 100, 100))));

		assertThat(walk.items()).hasSize(100);
		assertThat(walk.pagesLoaded()).isEqualTo(3);
		assertThat(walk.extraPages()).isEqualTo(2);
		assertThat(pauses.get()).isEqualTo(2);
		assertThat(walk.warning()).isNull();
	}

	@Test
	void stopsAtThePageCapAndWarns() {
		var walk = walker(2).walk(BASE, loader(Map.of(
				BASE, page(1, 48, 150),
				"https://x/shop/category/milk?pg=2", page(49, 96, 150))));

		assertThat(walk.items()).hasSize(96);
		assertThat(walk.pagesLoaded()).isEqualTo(2);
		assertThat(walk.warning()).contains("96 of 150").contains("page cap");
	}

	@Test
	void stopsWhenTheDailyBudgetRefusesAnExtraPage() {
		granted.set(1);
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 48, 150),
				"https://x/shop/category/milk?pg=2", page(49, 96, 150),
				"https://x/shop/category/milk?pg=3", page(97, 144, 150))));

		assertThat(walk.pagesLoaded()).isEqualTo(2);
		assertThat(walk.warning()).contains("budget");
	}

	@Test
	void stopsImmediatelyOnAnErrorOnALaterPageAndKeepsWhatItHas() {
		var walk = walker(8).walk(BASE, loader(Map.of(BASE, page(1, 48, 150))));

		assertThat(walk.items()).hasSize(48);
		assertThat(loaded).hasSize(2);                   // page 1 + the failing page 2, no retry, no page 3
		assertThat(walk.warning()).contains("48 of 150").contains("failed");
	}

	@Test
	void stopsOnAnEmptyLaterPage() {
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 48, 150),
				"https://x/shop/category/milk?pg=2", new LoadedPage<>(List.of(), "Showing 49 - 96 of 150 products"))));

		assertThat(walk.items()).hasSize(48);
		assertThat(loaded).hasSize(2);
		assertThat(walk.warning()).contains("no products");
	}

	@Test
	void aFirstPageWithoutTheMarkerIsTakenAsComplete() {
		var walk = walker(8).walk(BASE, loader(Map.of(BASE, new LoadedPage<>(List.of(1, 2, 3), "no count here"))));

		assertThat(walk.items()).hasSize(3);
		assertThat(loaded).hasSize(1);
		assertThat(walk.warning()).isNull();
	}

	@Test
	void anEmptyFirstPageIsReportedAsEmptyAndNothingElseIsLoaded() {
		var walk = walker(8).walk(BASE, loader(Map.of(BASE, new LoadedPage<>(List.of(), ""))));

		assertThat(walk.items()).isEmpty();
		assertThat(loaded).hasSize(1);
	}

	@Test
	void aCancelledRequestLoadsNoFurtherPage() {
		var w = new PaginatedCategoryWalker<Integer>(8, () -> true, () -> { }, () -> true);
		var walk = w.walk(BASE, loader(Map.of(BASE, page(1, 48, 150))));

		assertThat(loaded).hasSize(1);
		assertThat(walk.warning()).contains("cancelled");
	}

	/** Live New World pages carry ~68 tiles for 50 listed products: the tile count must not end the walk. */
	@Test
	void moreTilesThanListedProductsDoesNotStopTheWalkEarly() {
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 68, 120, 50),
				"https://x/shop/category/milk?pg=2", page(101, 168, 120, 100),
				"https://x/shop/category/milk?pg=3", page(201, 268, 120, 120))));

		assertThat(walk.pagesLoaded()).isEqualTo(3);
		assertThat(walk.warning()).isNull();
	}

	private static LoadedPage<Integer> noLine(int from, int to) {
		return new LoadedPage<>(IntStream.rangeClosed(from, to).boxed().toList(), "no count here");
	}

	@Test
	void aLaterPageWithoutTheCountLineIsStillWalkedUsingTheKnownTotal() {
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 50, 227),
				"https://x/shop/category/milk?pg=2", page(51, 100, 227),
				"https://x/shop/category/milk?pg=3", page(101, 150, 227),
				"https://x/shop/category/milk?pg=4", noLine(151, 200),
				"https://x/shop/category/milk?pg=5", page(201, 227, 227))));

		assertThat(walk.pagesLoaded()).isEqualTo(5);
		assertThat(walk.items()).hasSize(227);
		assertThat(walk.warning()).isNull();
	}

	@Test
	void severalConsecutiveLinelessPagesStopOnceTheEstimateReachesTheTotal() {
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 50, 120),
				"https://x/shop/category/milk?pg=2", noLine(51, 100),
				"https://x/shop/category/milk?pg=3", noLine(101, 120))));

		assertThat(walk.pagesLoaded()).isEqualTo(3);
		assertThat(walk.warning()).isNull();
	}

	@Test
	void aLinelessPageWithoutTilesStopsTheWalk() {
		var walk = walker(8).walk(BASE, loader(Map.of(
				BASE, page(1, 50, 227),
				"https://x/shop/category/milk?pg=2", new LoadedPage<>(List.of(), "no count here"))));

		assertThat(walk.pagesLoaded()).isEqualTo(2);
		assertThat(walk.items()).hasSize(50);
		assertThat(walk.warning()).contains("no products");
	}

	@Test
	void linelessPagesStillObeyThePageCap() {
		var walk = walker(3).walk(BASE, loader(Map.of(
				BASE, page(1, 50, 500),
				"https://x/shop/category/milk?pg=2", noLine(51, 100),
				"https://x/shop/category/milk?pg=3", noLine(101, 150),
				"https://x/shop/category/milk?pg=4", noLine(151, 200))));

		assertThat(walk.pagesLoaded()).isEqualTo(3);
		assertThat(walk.warning()).contains("page cap");
	}

	private static LoadedPage<Integer> page(int firstTile, int lastTile, int total, int listedUpTo) {
		return new LoadedPage<>(IntStream.rangeClosed(firstTile, lastTile).boxed().toList(),
				"Showing " + (listedUpTo - 49) + " - " + listedUpTo + " of " + total + " products");
	}
}
