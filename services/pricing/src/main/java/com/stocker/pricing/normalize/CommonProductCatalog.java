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
		Set<String> words = ProductNormalizer.words(text);
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
				if (alias.size() > bestLength && words.containsAll(alias)) {
					best = product;
					bestLength = alias.size();
				}
			}
		}
		return Optional.ofNullable(best);
	}
}
