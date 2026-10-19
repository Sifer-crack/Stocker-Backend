package com.stocker.pricing.service.cache;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.pricing")
public class PricingCacheProperties {

	private Cache cache = new Cache();
	private Fallback fallback = new Fallback();
	private Refresh refresh = new Refresh();
	private Match match = new Match();
	private OnDemand onDemand = new OnDemand();

	/**
	 * Before answering a search for a common product, check how old each chain's data is and, for stale
	 * chains only, load just the one category page that product lives on. Heavily rate limited.
	 */
	@Getter
	@Setter
	public static class OnDemand {
		private boolean enabled = true;
		/** Data older than this is refreshed before it is shown. */
		private Duration staleAfter = Duration.ofHours(12);
		/** How long a search waits for the refresh; on timeout it answers with stale data (flagged) and finishes in the background. */
		private Duration deadline = Duration.ofSeconds(6);
		/** A category page is never re-requested within this period, whatever the outcome. */
		private Duration categoryCooldown = Duration.ofMinutes(30);
		/** Hard cap on on-demand page loads per chain per hour (all products together). */
		private int maxPageLoadsPerChainPerHour = 20;
	}

	/** GET /api/pricing/match: how ingested products are matched to a requested item. */
	@Getter
	@Setter
	public static class Match {
		/** Minimum name-similarity (0..1) for a product to count as THE match for a chain. */
		private double minScore = 0.6;
		/** Minimum similarity for a different product to be offered as an alternative. */
		private double alternativeMinScore = 0.3;
		private int maxAlternativesPerChain = 2;
		/** Ingested rows older than this are ignored. */
		private Duration maxAge = Duration.ofDays(7);
		/**
		 * The common-product (canonical) path shows the newest stored price per chain even when it is old,
		 * flagged stale and refreshed on demand, rather than hiding the chain; only data older than this is dropped.
		 */
		private Duration canonicalMaxAge = Duration.ofDays(90);
		/** At most one on-demand ingest run per this interval, however many lookups miss. */
		private Duration refreshCooldown = Duration.ofMinutes(30);
		/**
		 * Whether a lookup that finds nothing may start a scrape of every configured category. Off by
		 * default: supermarket requests come only from the scheduled, rate-limited ingest; a miss is
		 * just recorded in unmatched_search_terms.
		 */
		private boolean onDemandIngest = false;
	}

	@Getter
	@Setter
	public static class Cache {
		private Duration freshnessWindow = Duration.ofHours(24);
		private Duration l1Ttl = Duration.ofMinutes(5);
	}

	@Getter
	@Setter
	public static class Fallback {
		private Duration timeout = Duration.ofSeconds(3);
	}

	@Getter
	@Setter
	public static class Refresh {
		private String topic = "stocker.pricing.refresh-requests.v1";
	}
}
