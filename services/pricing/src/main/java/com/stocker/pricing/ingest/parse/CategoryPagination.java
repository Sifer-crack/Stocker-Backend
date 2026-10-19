package com.stocker.pricing.ingest.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Foodstuffs category page says about its own paging: the "Showing 1 - 48 of 112 products" line.
 * {@code to < total} means more pages follow.
 */
public record CategoryPagination(int from, int to, int total) {

	/** Regex source shared with the page template (the browser runs it case-insensitively, see ChainPageTemplate). */
	public static final String SHOWING_SOURCE =
			"showing\\s+(\\d+)\\s*[-\\u2013]\\s*(\\d+)\\s+of\\s+(\\d+)\\s+products?";
	private static final Pattern SHOWING = Pattern.compile("(?i)" + SHOWING_SOURCE);
	private static final Pattern PG_PARAM = Pattern.compile("([?&])pg=\\d+");

	public static Optional<CategoryPagination> parse(String pageText) {
		if (pageText == null) {
			return Optional.empty();
		}
		Matcher m = SHOWING.matcher(pageText);
		if (!m.find()) {
			return Optional.empty();
		}
		return Optional.of(new CategoryPagination(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
				Integer.parseInt(m.group(3))));
	}

	public boolean hasMore() {
		return to < total;
	}

	/** The same category URL pointing at page {@code page}: replaces an existing {@code pg=} or appends one. */
	public static String pageUrl(String url, int page) {
		Matcher m = PG_PARAM.matcher(url);
		if (m.find()) {
			return m.replaceFirst(m.group(1) + "pg=" + page);
		}
		return url + (url.contains("?") ? "&" : "?") + "pg=" + page;
	}
}
