package com.stocker.pricing.ingest.model;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which requests a chain's page loads may drop, and whether to log every request host (tuning aid).
 *
 * <p>Only passive resource types can ever be blocked ({@link #BLOCKABLE_TYPES}); documents, scripts, XHR and fetch
 * are never aborted by type, whatever the configuration says.
 */
public record LeanPageOptions(boolean enabled, Set<String> blockResourceTypes, List<String> blockHostPatterns,
		boolean logRequests) {

	/** Playwright resource types that may be aborted. Never document / script / xhr / fetch / websocket. */
	public static final Set<String> BLOCKABLE_TYPES = Set.of("image", "media", "font", "stylesheet", "texttrack",
			"manifest", "imageset");

	/** Nothing blocked, nothing logged: what a scraper built without configuration (tests, live checks) does. */
	public static final LeanPageOptions OFF = new LeanPageOptions(false, Set.of(), List.of(), false);

	public LeanPageOptions {
		blockResourceTypes = blockResourceTypes == null ? Set.of() : blockResourceTypes.stream()
				.map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(BLOCKABLE_TYPES::contains)
				.collect(Collectors.toUnmodifiableSet());
		blockHostPatterns = blockHostPatterns == null ? List.of() : blockHostPatterns.stream()
				.map(p -> p.trim().toLowerCase(Locale.ROOT)).filter(p -> !p.isEmpty()).toList();
	}

	/** True when the context needs a route handler at all (blocking or request logging). */
	public boolean needsRouting() {
		return (enabled && (!blockResourceTypes.isEmpty() || !blockHostPatterns.isEmpty())) || logRequests;
	}
}
