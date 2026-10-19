package com.stocker.pricing.normalize;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a messy product name (store listing, pantry entry, shopper's request) into a stable set of
 * lower-case singular words: sizes and packaging noise removed, spelling and NZ/AU vocabulary
 * collapsed to one form. Two names for the same thing end up with the same words.
 */
public final class ProductNormalizer {

	private static final Set<String> NOISE = Set.of("not", "bagged", "bag", "in", "fresh", "loose", "pack", "pk",
			"each", "ea", "per", "the", "of", "and", "with", "nz", "new", "zealand", "australia", "australian",
			"imported", "prepacked", "packed", "size", "approx", "ex", "a", "an", "for");

	private static final Map<String, String> PHRASES = Map.of(
			"spring onion", "springonion",
			"green onion", "springonion",
			"scallion", "springonion",
			"sweet potato", "sweetpotato",
			"sweet corn", "sweetcorn",
			"full cream", "fullcream",
			"whole milk", "fullcream milk",
			"standard milk", "fullcream milk",
			"extra virgin", "extravirgin");

	private static final Map<String, String> WORDS = Map.ofEntries(
			Map.entry("yoghurt", "yogurt"),
			Map.entry("chili", "chilli"),
			Map.entry("chile", "chilli"),
			Map.entry("zucchini", "courgette"),
			Map.entry("aubergine", "eggplant"),
			Map.entry("cilantro", "coriander"),
			Map.entry("rockmelon", "cantaloupe"),
			Map.entry("kiwifruit", "kiwifruit"),
			Map.entry("kiwi", "kiwifruit"),
			Map.entry("capsicums", "capsicum"),
			Map.entry("biscuits", "biscuit"),
			Map.entry("cookies", "biscuit"),
			Map.entry("cookie", "biscuit"),
			Map.entry("crisps", "chip"),
			Map.entry("chips", "chip"),
			Map.entry("mince", "mince"),
			Map.entry("minced", "mince"),
			Map.entry("tomatoes", "tomato"),
			Map.entry("potatoes", "potato"),
			Map.entry("mangoes", "mango"),
			Map.entry("loaves", "loaf"));

	private ProductNormalizer() {
	}

	/** Ordered, de-duplicated words of the name. */
	public static Set<String> words(String name) {
		Set<String> words = new LinkedHashSet<>();
		if (name == null) {
			return words;
		}
		String text = PackSizeParser.strip(name).replace("'", "").replace("’", "");
		for (Map.Entry<String, String> phrase : PHRASES.entrySet()) {
			text = text.replace(phrase.getKey(), phrase.getValue());
		}
		for (String raw : text.split("[^a-z0-9]+")) {
			if (raw.isEmpty() || NOISE.contains(raw) || raw.chars().allMatch(Character::isDigit)) {
				continue;
			}
			words.add(singular(WORDS.getOrDefault(raw, raw)));
		}
		return words;
	}

	public static String normalizedName(String name) {
		return String.join(" ", words(name));
	}

	public static PackSize sizeOf(String text) {
		return PackSizeParser.parse(text).orElse(null);
	}

	private static String singular(String word) {
		if (!word.chars().allMatch(Character::isLetter) || word.length() <= 3) {
			return word;
		}
		String lower = word.toLowerCase(Locale.ROOT);
		if (lower.endsWith("ies") && lower.length() > 4) {
			return lower.substring(0, lower.length() - 3) + "y";
		}
		if (lower.endsWith("ss") || lower.endsWith("us") || lower.endsWith("is")) {
			return lower;
		}
		return lower.endsWith("s") ? lower.substring(0, lower.length() - 1) : lower;
	}
}
