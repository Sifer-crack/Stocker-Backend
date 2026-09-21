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
		private Duration maxAge = Duration.ofDays(3);
		/** At most one on-demand ingest run per this interval, however many lookups miss. */
		private Duration refreshCooldown = Duration.ofMinutes(30);
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
