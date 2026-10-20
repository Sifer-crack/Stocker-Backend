package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;

/**
 * Hands the scheduler the Browser a chain must use. Playwright objects are not thread-safe, so the scheduler
 * takes the lock for {@link #lockKey(ChainId)} around every use of the returned Browser.
 */
public interface ChainBrowsers extends AutoCloseable {

	/** The browser for this chain; may start it on first use. */
	Browser forChain(ChainId chain);

	/** Chains with the same key share a browser (and so a lock); a one-browser pool returns one key for all. */
	Object lockKey(ChainId chain);

	/** Closes every browser (and Playwright driver) this pool started. Never throws. */
	@Override
	void close();

	/** One browser for all chains: today's behaviour. The pool does not own the browser and does not close it. */
	static ChainBrowsers shared(Browser browser) {
		Object key = new Object();
		return new ChainBrowsers() {
			@Override
			public Browser forChain(ChainId chain) {
				return browser;
			}

			@Override
			public Object lockKey(ChainId chain) {
				return key;
			}

			@Override
			public void close() {
				// the Browser bean is closed by Spring
			}
		};
	}
}
