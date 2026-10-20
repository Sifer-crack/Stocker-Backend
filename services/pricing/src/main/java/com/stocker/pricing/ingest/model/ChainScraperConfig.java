package com.stocker.pricing.ingest.model;

import com.stocker.pricing.cancel.CancellationToken;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChainScraperConfig {

	private String storeId;
	private String storeName;
	private List<String> categoryUrls;
	private int maxPagesPerCategory;
	private int navigationTimeoutSeconds;
	/** Random pause between two page loads on the same chain; 0 = none. */
	private long pageDelayMinMillis;
	private long pageDelayMaxMillis;
	/**
	 * Cancelled when the request that triggered this scrape is abandoned: the scraper stops after the page being
	 * loaded. Null / unset = never cancelled (scheduled crawls).
	 */
	private CancellationToken cancellation;
	/**
	 * Asked before every page after the first of a multi-page category; true = the page may be loaded (and has been
	 * counted against the daily budget by the supplier). Null = always allowed (bounded by maxPagesPerCategory).
	 */
	private BooleanSupplier extraPageGate;

	/**
	 * The chain's circuit breaker: asked before every page (open = stop loading) and told how each response went.
	 * Null = never opens.
	 */
	private CrawlBrake crawlBrake;

	/** Called after each category with the products read from it (incremental save). Null = nobody listens. */
	private CategoryResultSink categoryResultSink;

	public CategoryResultSink getCategoryResultSink() {
		return categoryResultSink == null ? CategoryResultSink.NOOP : categoryResultSink;
	}

	public CrawlBrake getCrawlBrake() {
		return crawlBrake == null ? CrawlBrake.NONE : crawlBrake;
	}

	public BooleanSupplier getExtraPageGate() {
		return extraPageGate == null ? () -> true : extraPageGate;
	}

	public CancellationToken getCancellation() {
		return cancellation == null ? CancellationToken.NONE : cancellation;
	}
}
