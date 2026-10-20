package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.stocker.pricing.ingest.model.CrawlBrake;
import com.stocker.pricing.ingest.parse.BlockDetector;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Tells the circuit breaker how a page load went. A blocked response (HTTP 429 / 403 / 503, or a captcha page where
 * product tiles should be) is reported to the brake and raised as a {@link PlaywrightException}, so the page counts
 * as failed - never as a zero-result category - and is not retried in the same run.
 */
final class BlockGuard {

	private BlockGuard() {
	}

	static void failIfBlocked(Response response, String url, CrawlBrake brake) {
		int status = response == null ? 200 : response.status();
		if (!BlockDetector.isBlockedStatus(status)) {
			return;
		}
		String reason = "HTTP " + status;
		brake.onBlocked(reason, retryAfter(response));
		throw new PlaywrightException(reason + " (blocked or rate limited) at " + url);
	}

	/**
	 * Any other error status (404, 500, 502, 504 ...) is a failed page: it is not a block (the breaker is not told),
	 * not a good page (the breaker is not told either) and not a clean zero-product category (the PlaywrightException
	 * is caught by the scraper, which records the page as failed - never in zeroResultCategoryUrls).
	 */
	static void failIfHttpError(Response response, String url) {
		int status = response == null ? 200 : response.status();
		if (status >= 400) {
			throw new PlaywrightException("HTTP " + status + " (page failed) at " + url);
		}
	}

	/** Call when a page shows no products: a bot-check page is a block, an ordinary empty page is not. */
	static void failIfChallenge(Page page, String url, CrawlBrake brake) {
		String title = safe(page::title);
		String text = safe(() -> page.locator("body").innerText());
		if (BlockDetector.looksLikeChallenge(title, text)) {
			brake.onBlocked("captcha / bot-check page", null);
			throw new PlaywrightException("Captcha or bot-check page (blocked) at " + url);
		}
	}

	private static Duration retryAfter(Response response) {
		try {
			Map<String, String> headers = response.headers();      // names are lower-cased by Playwright
			return BlockDetector.parseRetryAfter(headers.get("retry-after"), Instant.now()).orElse(null);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String safe(java.util.function.Supplier<String> read) {
		try {
			return read.get();
		} catch (PlaywrightException e) {
			return "";
		}
	}
}
