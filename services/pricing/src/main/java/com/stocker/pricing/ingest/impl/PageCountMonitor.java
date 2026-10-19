package com.stocker.pricing.ingest.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory safety net for lean page loads: remembers the tile count last seen per URL in this process and flags a page
 * that now has fewer than {@link #DROP_FACTOR} of it. A real site rarely loses 20% of a category between two crawls,
 * but a blocklist that hides the product grid does it at once. Nothing is persisted; a restart forgets everything.
 */
final class PageCountMonitor {

	static final double DROP_FACTOR = 0.8;

	private final Map<String, Integer> lastTiles = new ConcurrentHashMap<>();

	/** Records {@code tiles} for {@code url}; returns a warning text when it dropped below 80% of the previous count. */
	Optional<String> record(String url, int tiles) {
		Integer previous = lastTiles.put(url, tiles);
		if (previous != null && previous > 0 && tiles < DROP_FACTOR * previous) {
			return Optional.of("tiles dropped from " + previous + " to " + tiles + " (below "
					+ Math.round(DROP_FACTOR * 100) + "%) at " + url
					+ " - a page template change or a too-aggressive request blocklist (STOCKER_INGEST_LEAN_PAGES=false to test)");
		}
		return Optional.empty();
	}
}
