package com.stocker.pricing.ingest.sitemap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryUrlMatcherTest {

	@Test
	void matchesKeywordInLastPathSegment() {
		assertTrue(CategoryUrlMatcher.matches(
				"https://www.paknsave.co.nz/shop/category/fridge-deli-and-eggs/milk/fresh-milk?pg=1", "milk"));
	}

	@Test
	void isCaseInsensitive() {
		assertTrue(CategoryUrlMatcher.matches(
				"https://www.woolworths.co.nz/shop/browse/fridge-deli/milk/full-cream-milk", "MILK"));
	}

	@Test
	void doesNotMatchEarlierPathSegments() {
		// "milk" appears in the parent segment, not the last one - should not match "eggs" here.
		assertFalse(CategoryUrlMatcher.matches(
				"https://www.paknsave.co.nz/shop/category/fridge-deli-and-eggs/milk?pg=1", "eggs"));
	}

	@Test
	void doesNotMatchUnrelatedCategory() {
		assertFalse(CategoryUrlMatcher.matches(
				"https://www.newworld.co.nz/shop/category/meat-poultry-and-seafood/chicken--poultry?pg=1", "milk"));
	}

	@Test
	void lastPathSegmentStripsQueryString() {
		assertTrue("fresh-milk".equals(
				CategoryUrlMatcher.lastPathSegment("https://www.newworld.co.nz/shop/category/milk/fresh-milk?pg=1")));
	}
}
