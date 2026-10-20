package com.stocker.pricing.normalize;

import java.util.List;
import java.util.Set;

/**
 * A search term (or a product name) split into the words used for name matching and the size
 * qualifiers that were taken out of it.
 *
 * @param original the text as given
 * @param words    product words only: sizes, pack counts and egg grades removed, normalised
 * @param wordList the same words in order, repeats kept
 * @param measure  weight or volume ("2L" = 2000 ML, "500g"), or null
 * @param count    item count ("12 pack", "dozen"), as an EACH pack size, or null
 * @param eggGrade NZ egg grade ("size 7" = 7), or null
 */
public record ParsedQuery(String original, Set<String> words, List<String> wordList, PackSize measure, PackSize count,
		Integer eggGrade) {

	public boolean hasQualifier() {
		return measure != null || count != null || eggGrade != null;
	}

	/** Same words, with an explicit pack size (e.g. a pantry product's selling weight) replacing the parsed measure. */
	public ParsedQuery withMeasure(PackSize explicit) {
		return explicit == null ? this : new ParsedQuery(original, words, wordList, explicit, count, eggGrade);
	}

	/**
	 * Whether a product meets every qualifier this query states. A product that does not state the
	 * qualified property at all does not meet it.
	 *
	 * @param product  the product's own name, parsed
	 * @param storedPack the pack size the index stored for it (preferred for the measure), or null
	 */
	public boolean satisfiedBy(ParsedQuery product, PackSize storedPack) {
		if (measure != null) {
			PackSize actual = storedPack != null && storedPack.unit() != PackUnit.EACH ? storedPack : product.measure();
			if (!measure.sameAs(actual)) {
				return false;
			}
		}
		if (count != null) {
			PackSize actual = product.count() != null ? product.count()
					: storedPack != null && storedPack.unit() == PackUnit.EACH ? storedPack : null;
			if (!count.sameAs(actual)) {
				return false;
			}
		}
		return eggGrade == null || eggGrade.equals(product.eggGrade());
	}
}
