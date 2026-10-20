package com.stocker.pricing.ingest.model;

import com.stocker.pricing.fetch.model.RawProduct;
import java.util.List;

/**
 * Told by a {@link com.stocker.pricing.ingest.ChainScraper} as soon as one category (all its pages) has been read,
 * so the scheduler can store the rows at once instead of when the whole chain run ends. A scraper that never calls
 * it still works: the scheduler then stores the run's products at the end, as before.
 */
@FunctionalInterface
public interface CategoryResultSink {

	CategoryResultSink NOOP = (categoryUrl, products) -> { };

	/** Must not throw into the scraper's page loop; implementations log and swallow their own failures. */
	void onCategory(String categoryUrl, List<RawProduct> products);
}
