package com.stocker.pricing.ingest.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ingest")
public class IngestProperties {

	private boolean enabled = false;
	private boolean dryRun = true;
	private String cron = "0 0 3 * * *";
	private String cronZone = "Pacific/Auckland";
	private boolean runOnStartup = false;
	private int navigationTimeoutSeconds = 30;
	private int maxPagesPerCategory = 5;
	private int sitemapCacheTtlDays = 7;
	/** Random pause between two page loads on the same chain (politeness / rate limit). */
	private long pageDelayMinMillis = 4000;
	private long pageDelayMaxMillis = 9000;
	/**
	 * Most category pages loaded per chain in one run. When more are configured the run takes the next
	 * window of them each day, so every category is still visited regularly but never all at once.
	 */
	/** 0 = every configured category on each run (full daily coverage). */
	private int maxCategoriesPerRun = 0;
	/** Hard cap on category pages loaded per chain per Auckland day, scheduled and on-demand together. */
	private int maxPagesPerChainPerDay = 80;
	/** Consecutive zero-product runs of one category page before it is quarantined. */
	private int quarantineAfterZeroRuns = 2;
	/** Days a quarantined page is skipped before it is retried once. */
	private int quarantineDays = 7;
	/** A run (>= 4 pages) with a larger zero share than this is treated as a site-wide problem and ignored. */
	private double quarantineSiteWideZeroShare = 0.5;
	private ChainConfig newworld = new ChainConfig();
	private ChainConfig paknsave = new ChainConfig();
	private ChainConfig woolworths = new ChainConfig();

	@Getter
	@Setter
	public static class ChainConfig {
		private boolean enabled = false;
		private String storeId = "";
		private String storeName = "";
		private List<String> categoryUrls = List.of();
	}
}
