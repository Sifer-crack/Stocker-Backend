package com.stocker.pricing.ingest.parse;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a category page says about its own paging: Foodstuffs' "Showing 1 - 48 of 112 products" line or Woolworths'
 * "1 to 36 of 264 products" line. {@code to < total} means more pages follow.
 */
public record CategoryPagination(int from, int to, int total) {

	/** Regex source shared with the page template (the browser runs it case-insensitively, see ChainPageTemplate). */
	public static final String SHOWING_SOURCE =
			"showing\\s+(\\d+)\\s*[-\\u2013]\\s*(\\d+)\\s+of\\s+(\\d+)\\s+products?";
	/** Woolworths NZ: "1 to 36 of 264 products" (the second page reads "37 to 72 of 264 products"). */
	public static final String WOOLWORTHS_SOURCE = "(\\d+)\\s+to\\s+(\\d+)\\s+of\\s+(\\d+)\\s+products?";
	/** Query parameter that carries the page number: Foodstuffs {@code pg}, Woolworths {@code page}. */
	public static final String FOODSTUFFS_PAGE_PARAM = "pg";
	public static final String WOOLWORTHS_PAGE_PARAM = "page";
	private static final Pattern SHOWING = Pattern.compile("(?i)" + SHOWING_SOURCE);
	private static final Pattern TO_FORM = Pattern.compile("(?i)" + WOOLWORTHS_SOURCE);

	public static Optional<CategoryPagination> parse(String pageText) {
		if (pageText == null) {
			return Optional.empty();
		}
		Matcher m = SHOWING.matcher(pageText);
		if (!m.find()) {
			m = TO_FORM.matcher(pageText);
			if (!m.find()) {
				return Optional.empty();
			}
		}
		return Optional.of(new CategoryPagination(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
				Integer.parseInt(m.group(3))));
	}

	public boolean hasMore() {
		return to < total;
	}

	/** The same category URL pointing at page {@code page} of a {@code pg=} chain (New World, PAK'nSAVE). */
	public static String pageUrl(String url, int page) {
		return pageUrl(url, page, FOODSTUFFS_PAGE_PARAM);
	}

	/** The same category URL pointing at page {@code page}: replaces an existing {@code param=} or appends one. */
	public static String pageUrl(String url, int page, String param) {
		Matcher m = Pattern.compile("([?&])" + Pattern.quote(param) + "=\\d+").matcher(url);
		if (m.find()) {
			return m.replaceFirst(Matcher.quoteReplacement(m.group(1) + param + "=" + page));
		}
		return url + (url.contains("?") ? "&" : "?") + param + "=" + page;
	}
}
