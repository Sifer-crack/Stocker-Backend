package com.stocker.pricing.normalize;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The list of the most common household products in New Zealand ({@code common-products.txt}),
 * and the matcher that maps any product text onto one of them. An entry matches when all words of
 * one of its aliases appear in the text and none of its exclude words do; the entry with the
 * longest matching alias wins ("coconut milk" beats "milk").
 */
public class CommonProductCatalog {

	public static final String RESOURCE = "/common-products.txt";

	private final Map<String, CommonProduct> byKey = new LinkedHashMap<>();

	public CommonProductCatalog(List<CommonProduct> products) {
		products.forEach(product -> byKey.put(product.key(), product));
	}

	public static CommonProductCatalog loadDefault() {
		try (InputStream in = CommonProductCatalog.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the classpath");
			}
			return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Lines: key|category|name|alias;alias|keyword|typical size|exclude;exclude. '#' starts a comment. */
	public static CommonProductCatalog parse(BufferedReader reader) throws IOException {
		List<CommonProduct> products = new ArrayList<>();
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] cols = line.split("\\|", -1);
			if (cols.length < 6) {
				throw new IllegalStateException("Bad common-products line: " + line);
			}
			List<Set<String>> aliases = new ArrayList<>();
			for (String alias : cols[3].split(";")) {
				Set<String> words = ProductNormalizer.words(alias);
				if (!words.isEmpty()) {
					aliases.add(words);
				}
			}
			Set<String> excludes = new LinkedHashSet<>();
			if (cols.length > 6) {
				Arrays.stream(cols[6].split(";")).forEach(word -> excludes.addAll(ProductNormalizer.words(word)));
			}
			products.add(new CommonProduct(cols[0].trim(), cols[1].trim(), cols[2].trim(), aliases, cols[4].trim(),
					ProductNormalizer.sizeOf(cols[5]), excludes));
		}
		return new CommonProductCatalog(products);
	}

	public List<CommonProduct> all() {
		return List.copyOf(byKey.values());
	}

	public Optional<CommonProduct> byKey(String key) {
		return Optional.ofNullable(byKey.get(key));
	}

	public Optional<CommonProduct> match(String text) {
		List<String> list = ProductNormalizer.wordList(text);
		Set<String> words = new LinkedHashSet<>(list);
		if (words.isEmpty()) {
			return Optional.empty();
		}
		CommonProduct best = null;
		int bestLength = 0;
		for (CommonProduct product : byKey.values()) {
			if (!Collections.disjoint(product.excludes(), words)) {
				continue;
			}
			for (Set<String> alias : product.aliasWords()) {
				if (alias.size() > bestLength && words.containsAll(alias)
						&& !looksLikeDish(list, alias, ownWords(product))) {
					best = product;
					bestLength = alias.size();
				}
			}
		}
		return Optional.ofNullable(best);
	}

	/**
	 * Whether {@code text} may be filed under {@code product}: none of its exclude words occur, and it is not a
	 * dish that merely contains the product's word ("Egg Fried Rice" is not eggs). Words the shopper typed
	 * ({@code queryWords}) are never held against a product. This does not require an alias to match, so it is safe
	 * for rows whose key came from an older catalog.
	 */
	public boolean accepts(CommonProduct product, String text, Set<String> queryWords) {
		List<String> list = ProductNormalizer.wordList(text);
		Set<String> words = new LinkedHashSet<>(list);
		if (!Collections.disjoint(product.excludes(), words)) {
			return false;
		}
		Set<String> anchors = new LinkedHashSet<>();
		for (Set<String> alias : product.aliasWords()) {
			if (words.containsAll(alias)) {
				anchors.addAll(alias);
			}
		}
		if (anchors.isEmpty()) {
			product.aliasWords().forEach(anchors::addAll);
		}
		Set<String> exempt = new LinkedHashSet<>(ownWords(product));
		exempt.addAll(queryWords);
		return !looksLikeDish(list, anchors, exempt);
	}

	/**
	 * Composite words: a product in which one of these FOLLOWS the category word is a dish or condiment made with it
	 * ("Egg Fried Rice", "Egg Noodles", "Egg Mayonnaise"), not an instance of the category. A dish word before the
	 * category word is a compound whose head is the category ("Sandwich Bread"), so it does not count. Words that are
	 * part of the category itself (aliases and name) or that the shopper typed are exempt. Chocolate-style items
	 * ("Chocolate Easter Egg") are handled by the per-product exclude words in common-products.txt, because chocolate
	 * is a legitimate word for many categories (ice cream, biscuits, milk drinks).
	 */
	public static final Set<String> DISH_WORDS = Set.of("rice", "noodle", "pasta", "sauce", "soup", "dressing",
			"mayonnaise", "mayo", "sandwich", "salad", "burger", "cake", "pie", "pizza", "curry", "sushi", "vinegar");

	/**
	 * The dish rule on its own, for terms that are not a common product: {@code anchors} are the words the product
	 * was matched on, {@code exempt} the dish words that are allowed anyway.
	 */
	public static boolean looksLikeDish(List<String> productWords, Set<String> anchors, Set<String> exempt) {
		int start = -1;
		for (int i = 0; i < productWords.size(); i++) {
			if (anchors.contains(productWords.get(i))) {
				start = i;
				break;
			}
		}
		if (start < 0) {
			return false;
		}
		for (int i = start + 1; i < productWords.size(); i++) {
			String word = productWords.get(i);
			if (DISH_WORDS.contains(word) && !exempt.contains(word) && !anchors.contains(word)) {
				return true;
			}
		}
		return false;
	}

	private static Set<String> ownWords(CommonProduct product) {
		Set<String> own = new LinkedHashSet<>(ProductNormalizer.words(product.name()));
		product.aliasWords().forEach(own::addAll);
		return own;
	}
}
