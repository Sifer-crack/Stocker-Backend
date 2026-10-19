package com.stocker.pricing.ingest.model;

import java.time.Duration;

/**
 * What a scraper tells the crawl about each page response, and asks before each page: the per-chain view of the
 * circuit breaker. A scraper must stop loading pages as soon as {@link #isOpen()} is true.
 */
public interface CrawlBrake {

	/** A brake that never opens (tests, callers that do not care). */
	CrawlBrake NONE = new CrawlBrake() {
		@Override
		public boolean isOpen() {
			return false;
		}

		@Override
		public void onSuccess() {
		}

		@Override
		public void onBlocked(String reason, Duration retryAfter) {
		}
	};

	/** True while the chain must not be requested. */
	boolean isOpen();

	/** A page came back normally (not blocked). */
	void onSuccess();

	/** A page came back blocked (HTTP 429 / 403 / 503 or a captcha page); {@code retryAfter} may be null. */
	void onBlocked(String reason, Duration retryAfter);
}
