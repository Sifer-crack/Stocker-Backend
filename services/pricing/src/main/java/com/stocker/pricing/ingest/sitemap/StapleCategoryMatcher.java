package com.stocker.pricing.ingest.sitemap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks, from a chain's sitemap category URLs, the pages whose name matches a staple keyword. Pure and offline:
 * the URLs come from {@link SitemapCategoryDiscoveryService}'s cache, the keywords from
 * {@code staple-category-keywords.txt} plus the sitemap-keyword column of {@code common-products.txt}.
 * Nothing here visits a supermarket; whether a picked page really lists products is left to the crawl
 * (a page that returns zero products twice in a row is quarantined by CategoryHealth).
 */
public final class StapleCategoryMatcher {

	public static final String KEYWORDS_RESOURCE = "/staple-category-keywords.txt";
	public static final String COMMON_PRODUCTS_RESOURCE = "/common-products.txt";

	private StapleCategoryMatcher() {
	}

	/** Matching pages, best match first (whole name equals a keyword, then shorter paths), at most {@code max}. */
	public static List<String> select(List<String> categoryUrls, Set<String> keywords, int max) {
		if (max <= 0 || keywords.isEmpty()) {
			return List.of();
		}
		return categoryUrls.stream()
				.filter(StapleCategoryMatcher::isCategoryPage)
				.map(url -> new Scored(url, score(CategoryUrlMatcher.lastPathSegment(url), keywords)))
				.filter(s -> s.score < Integer.MAX_VALUE)
				.sorted(Comparator.comparingInt(Scored::score).thenComparingInt(s -> s.url.length()).thenComparing(s -> s.url))
				.limit(max)
				.map(Scored::url)
				.toList();
	}

	public static boolean matches(String url, Set<String> keywords) {
		return isCategoryPage(url) && score(CategoryUrlMatcher.lastPathSegment(url), keywords) < Integer.MAX_VALUE;
	}

	private static boolean isCategoryPage(String url) {
		return url.contains("/shop/category/") || url.contains("/shop/browse/");
	}

	private static int score(String segment, Set<String> keywords) {
		if (keywords.contains(segment)) {
			return 0;
		}
		List<String> words = Arrays.stream(segment.split("-+")).filter(w -> !w.isEmpty()).toList();
		for (String keyword : keywords) {
			if (keyword.contains("-") ? segment.contains(keyword) : words.contains(keyword)) {
				return 1;
			}
		}
		return Integer.MAX_VALUE;
	}

	public static Set<String> loadKeywords() {
		Set<String> keywords = new LinkedHashSet<>();
		readLines(KEYWORDS_RESOURCE, line -> keywords.add(line.toLowerCase(Locale.ROOT)));
		readLines(COMMON_PRODUCTS_RESOURCE, line -> {
			String[] cols = line.split("\\|", -1);
			if (cols.length > 4 && !cols[4].isBlank()) {
				keywords.add(cols[4].trim().toLowerCase(Locale.ROOT));
			}
		});
		return keywords;
	}

	private static void readLines(String resource, java.util.function.Consumer<String> sink) {
		try (InputStream in = StapleCategoryMatcher.class.getResourceAsStream(resource)) {
			if (in == null) {
				return;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank() && !line.startsWith("#")) {
					sink.accept(line.trim());
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private record Scored(String url, int score) {
	}
}
