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

	@Test
	void readsTheWoolworthsToForm() {
		var first = CategoryPagination.parse("Sort by\nRelevance\n1 to 36 of 264 products\nOreo").orElseThrow();
		assertThat(first).isEqualTo(new CategoryPagination(1, 36, 264));
		assertThat(first.hasMore()).isTrue();
		var second = CategoryPagination.parse("37 to 72 of 264 products").orElseThrow();
		assertThat(second).isEqualTo(new CategoryPagination(37, 72, 264));
		var last = CategoryPagination.parse("253 to 264 of 264 products").orElseThrow();
		assertThat(last.hasMore()).isFalse();
		assertThat(CategoryPagination.parse("1 to 1 of 1 product")).isPresent();
	}

	@Test
	void pageUrlCanUseAnotherParameter() {
		String base = "https://www.woolworths.co.nz/shop/browse/pantry/biscuits-crackers/biscuits-cookies";
		assertThat(CategoryPagination.pageUrl(base, 2, "page")).isEqualTo(base + "?page=2");
		assertThat(CategoryPagination.pageUrl(base + "?page=2", 3, "page")).isEqualTo(base + "?page=3");
		assertThat(CategoryPagination.pageUrl(base + "?sort=p&page=1&q=1", 4, "page")).isEqualTo(base + "?sort=p&page=4&q=1");
		assertThat(CategoryPagination.pageUrl(base + "?sort=p", 2, "page")).isEqualTo(base + "?sort=p&page=2");
	}

	/** Real markup saved from the 2026-10-20 live probe of Woolworths biscuits-cookies (page 1 of 8). */
	@Test
	void theRealWoolworthsPaginationNavUsesPageLinksAndARelNextAnchor() throws Exception {
		String html = Files.readString(Path.of("src/test/resources/ingest/woolworths-pagination-nav.html"));
		assertThat(html).contains("aria-label=\"Pagination\"").contains("biscuits-cookies?page=2")
				.contains("rel=\"next\"").contains("1 to 36 of 264 products");
	}
}
