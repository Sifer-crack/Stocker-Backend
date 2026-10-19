package com.stocker.pricing.ingest;

import com.stocker.pricing.ingest.model.ChainScraperConfig;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Politeness delay between two page loads on the same chain: a random pause between the
 * configured minimum and maximum, so requests are neither bursty nor evenly spaced.
 */
public final class ScrapePacer {

	private ScrapePacer() {
	}

	public static void pauseBetweenPages(ChainScraperConfig config) {
		long min = Math.max(0, config.getPageDelayMinMillis());
		long max = Math.max(min, config.getPageDelayMaxMillis());
		if (max == 0) {
			return;
		}
		try {
			Thread.sleep(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
