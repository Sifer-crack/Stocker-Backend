package com.stocker.pricing.normalize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

	private static final Map<String, String> PHRASES = Map.ofEntries(
			Map.entry("chocolate milk", "chocolatemilk"),   // flavoured milk, not a chocolate block
			Map.entry("tim tam", "timtam"),
			Map.entry("tim-tam", "timtam"),
			Map.entry("weet bix", "weetbix"),
			Map.entry("weet-bix", "weetbix"),
			Map.entry("spring onion", "springonion"),
			Map.entry("green onion", "springonion"),
			Map.entry("scallion", "springonion"),
			Map.entry("sweet potato", "sweetpotato"),
			Map.entry("sweet corn", "sweetcorn"),
			Map.entry("full cream", "fullcream"),
			Map.entry("whole milk", "fullcream milk"),
			Map.entry("standard milk", "fullcream milk"),
			Map.entry("extra virgin", "extravirgin"));

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
			Map.entry("peaches", "peach"),
			Map.entry("loaves", "loaf"));

	private ProductNormalizer() {
	}

	/** Ordered, de-duplicated words of the name. */
	public static Set<String> words(String name) {
		return new LinkedHashSet<>(wordList(name));
	}

	/** Ordered words of the name, repeats kept (position matters to the dish guard). */
	public static List<String> wordList(String name) {
		if (name == null) {
			return new ArrayList<>();
		}
		// A pure function of the name, and a search normalises every stored name on every request: remember the result.
		if (WORD_CACHE.size() > WORD_CACHE_LIMIT) {
			WORD_CACHE.clear();
		}
		return new ArrayList<>(WORD_CACHE.computeIfAbsent(name, ProductNormalizer::computeWordList));
	}

	private static final int WORD_CACHE_LIMIT = 100_000;
	private static final java.util.concurrent.ConcurrentHashMap<String, List<String>> WORD_CACHE =
			new java.util.concurrent.ConcurrentHashMap<>();

	private static List<String> computeWordList(String name) {
		List<String> words = new ArrayList<>();
		String text = QueryParser.stripGrade(PackSizeParser.strip(name)).replace("'", "").replace("’", "");
		for (Map.Entry<String, String> phrase : PHRASES.entrySet()) {
			text = text.replace(phrase.getKey(), phrase.getValue());
		}
		for (String raw : text.split("[^a-z0-9]+")) {
			if (raw.isEmpty() || NOISE.contains(raw) || raw.chars().allMatch(Character::isDigit)) {
				continue;
			}
			words.add(singular(WORDS.getOrDefault(raw, raw)));
		}
		return List.copyOf(words);
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
