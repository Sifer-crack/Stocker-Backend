package com.stocker.pricing.ingest.sitemap;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Offline: the URLs below are made up in the shape of a chain's category sitemap. */
class StapleCategoryMatcherTest {

	private static final String NW = "https://www.newworld.co.nz/shop/category/";
	private static final Set<String> KEYWORDS = Set.of("milk", "rice", "peanut-butter", "eggs");

	@Test
	void picksPagesWhoseNameHasAStapleWordAsAWholeWord() {
		var picked = StapleCategoryMatcher.select(List.of(
				NW + "fridge-deli-and-eggs/milk/fresh-milk",
				NW + "pantry/rice-cakes",
				NW + "pantry/licorice",
				NW + "baby/baby-formula",
				NW + "pantry/spreads/peanut-butter-spreads"), KEYWORDS, 10);

		assertThat(picked).containsExactlyInAnyOrder(
				NW + "fridge-deli-and-eggs/milk/fresh-milk",
				NW + "pantry/rice-cakes",
				NW + "pantry/spreads/peanut-butter-spreads");
	}

	@Test
	void exactNameMatchesComeFirstAndTheCapApplies() {
		var picked = StapleCategoryMatcher.select(List.of(
				NW + "a/rice-crackers", NW + "a/rice", NW + "a/rice-and-grains-bulk"), KEYWORDS, 2);

		assertThat(picked).containsExactly(NW + "a/rice", NW + "a/rice-crackers");
	}

	@Test
	void ignoresUrlsThatAreNotCategoryPages() {
		assertThat(StapleCategoryMatcher.select(List.of("https://www.newworld.co.nz/recipes/milk-tart"), KEYWORDS, 5)).isEmpty();
	}

	@Test
	void woolworthsBrowsePagesAreCategoryPages() {
		var url = "https://www.woolworths.co.nz/shop/browse/pantry/rice";
		assertThat(StapleCategoryMatcher.select(List.of(url), KEYWORDS, 5)).containsExactly(url);
	}

	@Test
	void keywordsLoadFromTheBundledListAndTheCatalogue() {
		var keywords = StapleCategoryMatcher.loadKeywords();

		assertThat(keywords).contains("milk", "eggs", "bread", "peanut-butter");
	}
}
