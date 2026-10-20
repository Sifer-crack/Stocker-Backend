package com.stocker.pricing.ingest.impl;

import com.stocker.pricing.ingest.parse.CategoryPagination;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Walks every page of one category: loads page 1, reads "Showing A - B of M products" (Woolworths: "A to B of M
 * products") and follows {@code ?pg=2,3,...} (Woolworths {@code ?page=2,3,...}) until all M products are collected or a stop condition hits. Knows nothing about browsers:
 * the caller supplies a {@link Loader}. A single-page category costs exactly one load. Page 1 errors propagate;
 * an error, empty page or refused budget on a later page ends the walk with what is already collected.
 */
final class PaginatedCategoryWalker<T> {

	record LoadedPage<T>(List<T> items, String text) {
	}

	@FunctionalInterface
	interface Loader<T> {
		LoadedPage<T> load(String url);
	}

	record Walk<T>(List<T> items, int pagesLoaded, Integer expectedTotal, String warning) {
		int extraPages() {
			return Math.max(0, pagesLoaded - 1);
		}
	}

	private final int maxPages;
	private final BooleanSupplier extraPageGranted;
	private final Runnable pauseBetweenPages;
	private final BooleanSupplier cancelled;
	private final BiFunction<String, Integer, String> pageUrl;

	PaginatedCategoryWalker(int maxPages, BooleanSupplier extraPageGranted, Runnable pauseBetweenPages,
			BooleanSupplier cancelled) {
		this(maxPages, extraPageGranted, pauseBetweenPages, cancelled, CategoryPagination::pageUrl);
	}

	/** @param pageUrl builds the URL of page N from the category URL (the chain's page query parameter) */
	PaginatedCategoryWalker(int maxPages, BooleanSupplier extraPageGranted, Runnable pauseBetweenPages,
			BooleanSupplier cancelled, BiFunction<String, Integer, String> pageUrl) {
		this.pageUrl = pageUrl;
		this.maxPages = Math.max(1, maxPages);
		this.extraPageGranted = extraPageGranted;
		this.pauseBetweenPages = pauseBetweenPages;
		this.cancelled = cancelled;
	}

	Walk<T> walk(String url, Loader<T> loader) {
		LoadedPage<T> first = loader.load(url);
		List<T> items = new ArrayList<>(first.items());
		int pages = 1;
		Optional<CategoryPagination> info = CategoryPagination.parse(first.text());
		if (items.isEmpty() || info.isEmpty() || !info.get().hasMore()) {
			return new Walk<>(items, pages, info.map(CategoryPagination::total).orElse(null), null);
		}
		int total = info.get().total();
		int reached = info.get().to();
		String stop = null;
		// The page's own "Showing A - B of M" decides, never the tile count: the live grid carries about a third
		// more tiles than listed products (promoted / repeated tiles), so counting tiles would stop the walk early.
		while (info.isPresent() && info.get().hasMore()) {
			if (pages >= maxPages) {
				stop = "page cap of " + maxPages + " reached";
				break;
			}
			if (cancelled.getAsBoolean()) {
				stop = "cancelled";
				break;
			}
			if (!extraPageGranted.getAsBoolean()) {
				stop = "daily page budget used up";
				break;
			}
			pauseBetweenPages.run();
			LoadedPage<T> next;
			try {
				pages++;
				next = loader.load(pageUrl.apply(url, pages));
			} catch (RuntimeException e) {
				stop = "page " + pages + " failed: " + String.valueOf(e.getMessage()).lines().findFirst().orElse("");
				break;
			}
			if (next.items().isEmpty()) {
				stop = "page " + pages + " had no products";
				break;
			}
			items.addAll(next.items());
			info = CategoryPagination.parse(next.text());
			reached = info.map(CategoryPagination::to).orElse(reached);
			if (info.isEmpty()) {
				stop = "page " + pages + " has no 'Showing A - B of M' line";
			}
		}
		String warning = stop != null && (info.isEmpty() || info.get().hasMore())
				? url + ": read " + reached + " of " + total + " listed products ("
						+ stop + ")"
				: null;
		return new Walk<>(items, pages, total, warning);
	}
}
