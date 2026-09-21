package com.stocker.pricing.service.match;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic similarity between a requested item ("full cream milk 2L") and a product name
 * ("Anchor Blue Top Milk 2L"): the share of the request's words the product name contains, lightly
 * penalised for extra words, and capped when both state a pack size and the sizes differ.
 * A product must share at least one non-size word with the request, so "2L" alone never matches.
 */
public final class NameMatcher {

	private static final Pattern SIZE_UNIT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:litres?|liters?|ltr|lt|l)\\b");
	private static final Pattern SIZE_TOKEN = Pattern.compile("\\d+(?:\\.\\d+)?(?:l|ml|g|kg)");
	private static final double SIZE_MISMATCH_CAP = 0.4;
	private static final double EXTRA_WORD_PENALTY = 0.02;
	private static final double MAX_EXTRA_PENALTY = 0.15;

	private NameMatcher() {
	}

	/** 0..1; 0 when there is no shared non-size word. */
	public static double score(String requested, String productName) {
		Set<String> query = tokens(requested);
		Set<String> name = tokens(productName);
		if (query.isEmpty() || name.isEmpty()) {
			return 0.0;
		}
		Set<String> shared = new HashSet<>(query);
		shared.retainAll(name);
		long sharedWords = shared.stream().filter(token -> !isSize(token)).count();
		if (sharedWords == 0) {
			return 0.0;
		}
		double coverage = (double) shared.size() / query.size();
		long extraWords = name.stream().filter(token -> !query.contains(token) && !isSize(token)).count();
		double score = coverage - Math.min(MAX_EXTRA_PENALTY, EXTRA_WORD_PENALTY * extraWords);

		Set<String> querySizes = sizes(query);
		Set<String> nameSizes = sizes(name);
		if (!querySizes.isEmpty() && !nameSizes.isEmpty() && Collections.disjoint(querySizes, nameSizes)) {
			score = Math.min(score, SIZE_MISMATCH_CAP);
		}
		return Math.max(0.0, Math.min(1.0, score));
	}

	static Set<String> tokens(String text) {
		if (text == null) {
			return Set.of();
		}
		String normalized = text.toLowerCase(Locale.ROOT).replace("'", "");
		normalized = SIZE_UNIT.matcher(normalized).replaceAll("$1l");
		Set<String> tokens = new HashSet<>();
		for (String raw : normalized.split("[^a-z0-9.]+")) {
			String token = raw.replaceAll("^\\.+|\\.+$", "");
			if (token.isEmpty()) {
				continue;
			}
			tokens.add(singular(token));
		}
		return tokens;
	}

	private static String singular(String token) {
		boolean word = token.chars().allMatch(Character::isLetter);
		return word && token.length() > 3 && token.endsWith("s") && !token.endsWith("ss")
				? token.substring(0, token.length() - 1)
				: token;
	}

	private static boolean isSize(String token) {
		return SIZE_TOKEN.matcher(token).matches();
	}

	private static Set<String> sizes(Set<String> tokens) {
		Set<String> sizes = new HashSet<>();
		tokens.stream().filter(NameMatcher::isSize).forEach(sizes::add);
		return sizes;
	}
}
