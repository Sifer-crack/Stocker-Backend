package com.stocker.pricing.ingest.sitemap;

import java.util.Locale;

/** Pure keyword matching against a category-page URL's last path segment. */
final class CategoryUrlMatcher {

	private CategoryUrlMatcher() {
	}

	static boolean matches(String url, String keyword) {
		return lastPathSegment(url).contains(keyword.toLowerCase(Locale.ROOT));
	}

	static String lastPathSegment(String url) {
		String withoutQuery = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
		int lastSlash = withoutQuery.lastIndexOf('/');
		return (lastSlash >= 0 ? withoutQuery.substring(lastSlash + 1) : withoutQuery).toLowerCase(Locale.ROOT);
	}
}
