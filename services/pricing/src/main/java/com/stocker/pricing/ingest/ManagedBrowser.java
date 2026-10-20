package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;

/** A Browser together with whatever must be closed with it (its own Playwright driver process). */
public interface ManagedBrowser extends AutoCloseable {

	Browser browser();

	@Override
	void close();
}
