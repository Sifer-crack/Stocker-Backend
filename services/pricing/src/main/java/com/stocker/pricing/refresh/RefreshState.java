package com.stocker.pricing.refresh;

import java.util.Locale;

/** What the on-demand refresh of one chain's page is doing for a product or term. */
public enum RefreshState {

	/** The page load is running or queued. */
	PENDING,
	/** The page load finished (it may have found nothing). */
	DONE,
	/** The page load threw. */
	FAILED,
	/** No load was started: cooldown, hourly cap, refresh disabled, request gone, or no category page for the chain. */
	SKIPPED;

	public String wire() {
		return name().toLowerCase(Locale.ROOT);
	}
}
