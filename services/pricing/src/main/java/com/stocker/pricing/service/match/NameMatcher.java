package com.stocker.pricing.service.match;

import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.ParsedQuery;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.normalize.QueryParser;
import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic similarity between a requested item ("full cream milk 2L") and a product name
 * ("Anchor Blue Top Milk 2L"): the share of the request's words the product name contains, lightly
 * penalised for extra words, and capped when the request states a size, pack count or egg grade the
 * product does not have. Sizes, counts and grades are not words (see {@link QueryParser}), so they
 * never count as shared or extra. A product must share at least one word with the request.
 */
public final class NameMatcher {

	static final double SIZE_MISMATCH_CAP = 0.4;
	private static final double EXTRA_WORD_PENALTY = 0.02;
	private static final double MAX_EXTRA_PENALTY = 0.15;

	private NameMatcher() {
	}

	/** 0..1; 0 when there is no shared word. */
	public static double score(String requested, String productName) {
		return score(QueryParser.parse(requested), productName, null);
	}

	/** @param storedPack the pack size the index stored for the product (preferred over parsing the name), or null */
	public static double score(ParsedQuery query, String productName, PackSize storedPack) {
		double words = wordScore(query, productName);
		if (words == 0.0) {
			return 0.0;
		}
		return qualifiersMatch(query, productName, storedPack) ? words : Math.min(words, SIZE_MISMATCH_CAP);
	}

	/** Word overlap only, ignoring qualifiers. */
	public static double wordScore(ParsedQuery query, String productName) {
		Set<String> queryWords = query.words();
		Set<String> nameWords = ProductNormalizer.words(productName);
		if (queryWords.isEmpty() || nameWords.isEmpty()) {
			return 0.0;
		}
		Set<String> shared = new HashSet<>(queryWords);
		shared.retainAll(nameWords);
		if (shared.isEmpty()) {
			return 0.0;
		}
		double coverage = (double) shared.size() / queryWords.size();
		long extraWords = nameWords.stream().filter(word -> !queryWords.contains(word)).count();
		double score = coverage - Math.min(MAX_EXTRA_PENALTY, EXTRA_WORD_PENALTY * extraWords);
		return Math.max(0.0, Math.min(1.0, score));
	}

	/** True when the query states no qualifier, or the product has every one of them. */
	public static boolean qualifiersMatch(ParsedQuery query, String productName, PackSize storedPack) {
		return !query.hasQualifier() || query.satisfiedBy(QueryParser.parse(productName), storedPack);
	}
}
