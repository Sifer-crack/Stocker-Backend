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

	/**
	 * A page request is about to be sent (before the answer is known). Lets the caller count the requests really made,
	 * including those that time out or fail with an error status and so never reach {@link #onSuccess()} or
	 * {@link #onBlocked}. Default: ignored.
	 */
	default void onPageRequested() {
	}

	/** True while the chain must not be requested. */
	boolean isOpen();

	/** A page came back normally: HTTP status below 400 and at least one product tile. */
	void onSuccess();

	/** A page came back blocked (HTTP 429 / 403 / 503 or a captcha page); {@code retryAfter} may be null. */
	void onBlocked(String reason, Duration retryAfter);
}
