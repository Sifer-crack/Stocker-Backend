package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.DailyPageBudget;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ingest")
public class IngestProperties {

	private boolean enabled = false;
	private boolean dryRun = true;
	/** Rows stored per database transaction when a scraped category is persisted (one batch = a fixed number of round trips). */
	private int persistBatchSize = 500;
	/**
	 * Pauses (ms) before each retry of a chunk whose save failed on a dropped database connection (serverless Postgres
	 * restarts its compute): one entry = one retry, so the default retries three times, after 2 s, 5 s and 10 s.
	 */
	private List<Long> persistRetryBackoffMillis = List.of(2000L, 5000L, 10000L);
	private String cron = "0 0 3 * * *";
	private String cronZone = "Pacific/Auckland";
	private boolean runOnStartup = false;
	/**
	 * Crawl the three chains at the same time (one thread and one Firefox per chain) instead of one after another.
	 * Needs roughly three times the browser memory: see docker-compose.crawl.yml. Pacing, the daily page budget and the
	 * circuit breaker stay per chain.
	 */
	private boolean parallelChains = false;
	private int navigationTimeoutSeconds = 30;
	/** Most pages (pg=1,2,...) walked for one multi-page category; every page after the first counts against the daily cap. */
	private int maxPagesPerCategory = 8;
	private int sitemapCacheTtlDays = 7;
	/** Random pause between two page loads on the same chain (politeness / rate limit). */
	private long pageDelayMinMillis = 20_000;
	private long pageDelayMaxMillis = 40_000;
	/** Consecutive blocked responses (429 / 403 / 503 / captcha) that open a chain's circuit breaker. */
	private int breakerBlockedThreshold = 3;
	/** First cool-down of an open breaker; doubles on each consecutive trip. */
	private Duration breakerCooldown = Duration.ofHours(6);
	/** Longest cool-down (also bounds a Retry-After header). */
	private Duration breakerMaxCooldown = Duration.ofHours(24);
	/**
	 * Most category pages loaded per chain in one run. When more are configured the run takes the next
	 * window of them each day, so every category is still visited regularly but never all at once.
	 */
	/** 0 = every configured category on each run (full daily coverage). */
	private int maxCategoriesPerRun = 0;
	/** Hard cap on category pages loaded per chain per Auckland day, scheduled and on-demand together. */
	private int maxPagesPerChainPerDay = DailyPageBudget.DEFAULT_CAP_PER_CHAIN;
	/**
	 * When the category list is longer than the day's budget, this many pages are held back from the first page of
	 * each category so the later pages of multi-page staples can still be walked.
	 */
	private int extraPagesReservePerRun = 20;
	/** A page crawled less than this many hours ago goes to the back of the queue (cut first when the cap bites). */
	private int recrawlFreshnessHours = 20;
	/** Add sitemap category pages that match a staple keyword to the crawl (unverified; CategoryHealth drops dead ones). */
	private boolean stapleDiscoveryEnabled = true;
	/** Most sitemap-discovered staple pages added per chain. */
	private int maxDiscoveredPagesPerChain = 40;
	/** Consecutive zero-product runs of one category page before it is quarantined. */
	private int quarantineAfterZeroRuns = 2;
	/** Days a quarantined page is skipped before it is retried once. */
	private int quarantineDays = 7;
	/** A run (>= 4 pages) with a larger zero share than this is treated as a site-wide problem and ignored. */
	private double quarantineSiteWideZeroShare = 0.5;
	/** Lean page loads (request blocking). OFF by default: unproven on the real sites. false = every request is allowed. */
	private boolean leanPages = false;
	/** Log, per page, the request hosts with resource-type counts and which were aborted (tuning aid). */
	private boolean logRequests = false;
	/** Shared lean-page defaults; a chain can replace either list under {@code app.ingest.<chain>.lean}. */
	private LeanConfig lean = LeanConfig.sharedDefaults();
	private ChainConfig newworld = new ChainConfig();
	private ChainConfig paknsave = new ChainConfig();
	private ChainConfig woolworths = new ChainConfig();

	/** The pause range of one chain: its own setting, else the shared one (a max is never below the min). */
	public long pageDelayMinMillis(ChainId chain) {
		Long own = chainConfig(chain).getPageDelayMinMillis();
		return Math.max(0, own != null ? own : pageDelayMinMillis);
	}

	public long pageDelayMaxMillis(ChainId chain) {
		Long own = chainConfig(chain).getPageDelayMaxMillis();
		return Math.max(pageDelayMinMillis(chain), own != null ? own : pageDelayMaxMillis);
	}

	/** What this chain's page loads may drop: kill switch AND the chain's own flag, chain lists else the shared ones. */
	public LeanPageOptions leanOptions(ChainId chain) {
		LeanConfig own = chainConfig(chain).getLean();
		boolean chainEnabled = own.getEnabled() == null || own.getEnabled();
		return new LeanPageOptions(leanPages && chainEnabled,
				Set.copyOf(own.getBlockResourceTypes() != null ? own.getBlockResourceTypes() : lean.getBlockResourceTypes()),
				own.getBlockHostPatterns() != null ? own.getBlockHostPatterns() : lean.getBlockHostPatterns(),
				logRequests);
	}

	/** Page cap for one category on this chain: its own setting, else the shared one. */
	public int maxPagesPerCategory(ChainId chain) {
		Integer own = chainConfig(chain).getMaxPagesPerCategory();
		return own != null && own > 0 ? own : maxPagesPerCategory;
	}

	private ChainConfig chainConfig(ChainId chain) {
		return switch (chain) {
			case NEWWORLD -> newworld;
			case PAKNSAVE -> paknsave;
			case WOOLWORTHS -> woolworths;
		};
	}

	@Getter
	@Setter
	public static class ChainConfig {
		/** Per-chain page pause; null = use the shared app.ingest.page-delay-min/max-millis. */
		private Long pageDelayMinMillis;
		private Long pageDelayMaxMillis;
		/** Per-chain page cap for one category; null / 0 = use app.ingest.max-pages-per-category. */
		private Integer maxPagesPerCategory;
		private boolean enabled = false;
		private String storeId = "";
		private String storeName = "";
		private List<String> categoryUrls = List.of();
		/** Per-chain lean-page settings; unset values inherit the shared ones. */
		private LeanConfig lean = new LeanConfig();
	}

	/**
	 * Lean page loads. Resource types are Playwright's ({@code image, media, font, stylesheet}); only passive types are
	 * ever blocked. Host patterns are case-insensitive substrings of the request host, never applied to the chain's own
	 * domain. Shared defaults are non-null; on a chain, null = inherit.
	 */
	@Getter
	@Setter
	public static class LeanConfig {
		private Boolean enabled;
		private List<String> blockResourceTypes;
		private List<String> blockHostPatterns;

		public LeanConfig() {
		}

		/** Generic analytics / ads / tracking hosts; none of them can serve a chain's product grid. */
		public static final List<String> DEFAULT_HOST_PATTERNS = List.of("google-analytics", "googletagmanager",
				"googletagservices", "googlesyndication", "doubleclick", "adservice.google", "facebook.net",
				"facebook.com", "connect.facebook", "hotjar", "segment.io", "segment.com", "optimizely", "newrelic",
				"nr-data.net", "clarity.ms", "bat.bing.com", "criteo", "taboola", "outbrain", "quantserve",
				"scorecardresearch", "fullstory", "mouseflow", "mixpanel", "amplitude.com", "snapchat", "tiktok",
				"ads-twitter", "analytics.twitter", "sentry.io", "datadoghq", "tealiumiq",
				"adobedtm", "demdex", "omtrdc");

		static LeanConfig sharedDefaults() {
			LeanConfig shared = new LeanConfig();
			shared.blockResourceTypes = List.of("image", "media", "font");
			shared.blockHostPatterns = DEFAULT_HOST_PATTERNS;
			return shared;
		}
	}
}
