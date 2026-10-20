package com.stocker.pricing.normalize;

import java.util.List;
import java.util.Set;

/**
 * One entry of the NZ staples list. {@code keyword} is the category word to look for in a chain's
 * sitemap (e.g. "milk" -> .../milk/fresh-milk); it tells the scrape planner which category pages
 * this product lives on, so many products share one page visit.
 */
public record CommonProduct(String key, String category, String name, List<Set<String>> aliasWords,
		String keyword, PackSize typicalSize, Set<String> excludes) {
}
