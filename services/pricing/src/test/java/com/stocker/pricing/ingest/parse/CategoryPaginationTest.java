package com.stocker.pricing.ingest.parse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryPaginationTest {

	@Test
	void readsTheShowingLineFromPageText() {
		var p = CategoryPagination.parse("Fresh milk\nShowing 1 - 48 of 112 products\nPage 1 of 3").orElseThrow();
		assertThat(p.from()).isEqualTo(1);
		assertThat(p.to()).isEqualTo(48);
		assertThat(p.total()).isEqualTo(112);
		assertThat(p.hasMore()).isTrue();
	}

	@Test
	void aSinglePageCategoryHasNoMore() {
		var p = CategoryPagination.parse("Showing 1 - 33 of 33 products").orElseThrow();
		assertThat(p.hasMore()).isFalse();
	}

	@Test
	void toleratesEnDashAndSingularProduct() {
		assertThat(CategoryPagination.parse("Showing 1 – 1 of 1 product")).isPresent();
	}

	@Test
	void missingOrNullTextIsEmpty() {
		assertThat(CategoryPagination.parse("no marker here")).isEmpty();
		assertThat(CategoryPagination.parse(null)).isEmpty();
	}

	@Test
	void pageUrlReplacesOrAppendsPg() {
		assertThat(CategoryPagination.pageUrl("https://x/shop/category/a/b?pg=1", 3)).isEqualTo("https://x/shop/category/a/b?pg=3");
		assertThat(CategoryPagination.pageUrl("https://x/shop/category/a/b", 2)).isEqualTo("https://x/shop/category/a/b?pg=2");
		assertThat(CategoryPagination.pageUrl("https://x/c?sort=p&pg=1&q=1", 2)).isEqualTo("https://x/c?sort=p&pg=2&q=1");
	}

	private static String probe(String name) throws Exception {
		return Files.readString(Path.of("src/test/resources/ingest/probe", name));
	}

	/** Real page text and pagination markup saved from the 2026-10-20 live probe of chips-nuts--snacks. */
	@Test
	void readsTheRealNewWorldAndPaknsavePages() throws Exception {
		assertThat(CategoryPagination.parse(probe("newworld-page1.txt")).orElseThrow())
				.isEqualTo(new CategoryPagination(1, 50, 451));
		var last = CategoryPagination.parse(probe("newworld-page9.txt")).orElseThrow();
		assertThat(last).isEqualTo(new CategoryPagination(401, 449, 449));
		assertThat(last.hasMore()).isFalse();
		assertThat(CategoryPagination.parse(probe("paknsave-page1.txt")).orElseThrow().hasMore()).isTrue();
		assertThat(CategoryPagination.parse(probe("paknsave-page10.txt")).orElseThrow())
				.isEqualTo(new CategoryPagination(451, 500, 520));
	}

	@Test
	void theRealPaginationNavUsesPgLinksAndNextPageLabel() throws Exception {
		String text = probe("newworld-page1.txt");
		assertThat(text).contains("aria-label=\"pagination\"").contains("href=\"?pg=2\"").contains("Next page");
		assertThat(CategoryPagination.pageUrl("https://www.newworld.co.nz/shop/category/pantry/chips-nuts--snacks?pg=1", 2))
				.isEqualTo("https://www.newworld.co.nz/shop/category/pantry/chips-nuts--snacks?pg=2");
	}
}
