package com.stocker.pricing.service.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameMatcherTest {

	@Test
	void aProductContainingAllTheRequestedWordsAndSizeScoresHigh() {
		assertTrue(NameMatcher.score("milk 2L", "Anchor Blue Top Milk 2L") >= 0.6);
		assertTrue(NameMatcher.score("full cream milk 2 litre", "Meadow Fresh Full Cream Milk 2L") >= 0.9);
	}

	@Test
	void sizeSpellingsAreNormalised() {
		assertEquals(NameMatcher.score("milk 2L", "Milk 2L"), NameMatcher.score("milk 2 litres", "Milk 2ltr"));
	}

	@Test
	void aDifferentPackSizeIsNeverAnExactMatchButStaysAnAlternativeCandidate() {
		double score = NameMatcher.score("milk 2L", "Anchor Trim Milk 1L");
		assertTrue(score <= 0.4, "was " + score);
		assertTrue(score >= 0.3, "was " + score);
	}

	@Test
	void aSizeAloneIsNotAMatch() {
		assertEquals(0.0, NameMatcher.score("milk 2L", "Oat Drink 2L"));
	}

	@Test
	void pluralsAndApostrophesAreIgnored() {
		assertTrue(NameMatcher.score("tim tam", "Arnott's Tim Tams Original 200g") >= 0.9);
	}

	@Test
	void blankInputScoresZero() {
		assertEquals(0.0, NameMatcher.score("", "Milk"));
		assertEquals(0.0, NameMatcher.score("milk", null));
	}

	@Test
	void qualifiersAreNotWordsAndAMissingOrDifferentOneCapsTheScore() {
		assertTrue(NameMatcher.score("eggs size 7 12 pack", "Pams Free Range Eggs Size 7 12 Pack") >= 0.9);
		assertTrue(NameMatcher.score("eggs size 7", "Farm Fresh Barn Eggs Size 6 12pk") <= 0.4);
		assertTrue(NameMatcher.score("milk 2L", "Fresh Milk") <= 0.4, "the product does not state the asked size");
		assertEquals(1.0, NameMatcher.score("milk", "Milk 2L"), 0.0001, "no qualifier: every size is as relevant");
		assertEquals(NameMatcher.score("milk", "Milk 1L"), NameMatcher.score("milk", "Milk 3L"));
	}

	@Test
	void spacingOfBrandNamesDoesNotMatter() {
		assertTrue(NameMatcher.score("timtam", "Arnott's Tim Tam Original 200g") >= 0.9);
		assertTrue(NameMatcher.score("weetbix", "Sanitarium Weet-Bix 375g") >= 0.9);
	}
}
