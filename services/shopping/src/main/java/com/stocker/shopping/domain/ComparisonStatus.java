package com.stocker.shopping.domain;

import java.util.Locale;

/**
 * Where a shopping-list item stands in its price comparison. Stored as the enum name;
 * {@link #wire()} is the lower-case form used in REST responses and gateway pushes.
 */
public enum ComparisonStatus {

	/** No comparison yet: the initial state, or pricing was slow/unreachable and a backfill is awaited. */
	PENDING,

	/** A comparison (cheapest price plus every compared price) is stored on the item. */
	AVAILABLE,

	/** Pricing rejected the request outright (for example, outside the service area). */
	UNAVAILABLE;

	public String wire() {
		return name().toLowerCase(Locale.ROOT);
	}
}
