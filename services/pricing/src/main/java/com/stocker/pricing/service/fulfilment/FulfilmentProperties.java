package com.stocker.pricing.service.fulfilment;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-chain tiered delivery / click-and-collect fees, with optional per-island overrides.
 * Values are data (application.yml), never code.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.fulfilment")
public class FulfilmentProperties {

	/** Supported region (lower-case) -> island (north / south). An unknown region uses the chain defaults. */
	private Map<String, String> regionIslands = new LinkedHashMap<>();

	/** Keyed by lower-case chain id (newworld, paknsave, woolworths). */
	private Map<String, ChainFees> chains = new LinkedHashMap<>();

	/** Fees for a chain (case-insensitive), or null when none are configured. */
	public ChainFees forChain(String chainId) {
		if (chainId == null || chains == null) {
			return null;
		}
		for (Map.Entry<String, ChainFees> entry : chains.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(chainId)) {
				return entry.getValue();
			}
		}
		return null;
	}

	/** Island (lower-case) for a region, or null when the region is unknown. */
	public String islandOf(String region) {
		if (region == null || regionIslands == null) {
			return null;
		}
		String key = region.trim().toLowerCase(Locale.ROOT);
		for (Map.Entry<String, String> entry : regionIslands.entrySet()) {
			if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(key)) {
				return entry.getValue() == null ? null : entry.getValue().trim().toLowerCase(Locale.ROOT);
			}
		}
		return null;
	}

	/** One rung of a fee ladder: applies from this items total (inclusive) up to the next rung's {@code from}. */
	@Getter
	@Setter
	public static class Tier {
		private BigDecimal from = BigDecimal.ZERO;
		private BigDecimal fee = BigDecimal.ZERO;

		public Tier() {
		}

		public Tier(BigDecimal from, BigDecimal fee) {
			this.from = from;
			this.fee = fee;
		}
	}

	/** Per-island override of a chain's ladders; a method left empty falls back to the chain default. */
	@Getter
	@Setter
	public static class IslandFees {
		private List<Tier> delivery;
		private List<Tier> clickAndCollect;
	}

	@Getter
	@Setter
	public static class ChainFees {
		/** Null or empty = delivery not offered. */
		private List<Tier> delivery;
		/** Null or empty = click and collect not offered. */
		private List<Tier> clickAndCollect;
		/** Keyed by island (north / south). */
		private Map<String, IslandFees> islands = new LinkedHashMap<>();

		/** True when the chain offers at least one method on {@code island} (a non-empty ladder). */
		public boolean offersAnyMethod(String island) {
			for (FulfilmentMethod method : FulfilmentMethod.values()) {
				List<Tier> ladder = method == FulfilmentMethod.DELIVERY
						? pick(island, IslandFees::getDelivery, delivery)
						: pick(island, IslandFees::getClickAndCollect, clickAndCollect);
				if (ladder != null && !ladder.isEmpty()) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Fee for {@code method} at {@code itemsTotal} on {@code island} (null island = chain default), or null
		 * when the chain does not offer the method. The island override wins when it defines the method.
		 */
		public BigDecimal feeFor(FulfilmentMethod method, BigDecimal itemsTotal, String island) {
			List<Tier> ladder = switch (method) {
				case DELIVERY -> pick(island, IslandFees::getDelivery, delivery);
				case CLICK_AND_COLLECT -> pick(island, IslandFees::getClickAndCollect, clickAndCollect);
			};
			return tierFee(ladder, itemsTotal);
		}

		private List<Tier> pick(String island, java.util.function.Function<IslandFees, List<Tier>> getter,
				List<Tier> fallback) {
			if (island != null && islands != null) {
				for (Map.Entry<String, IslandFees> entry : islands.entrySet()) {
					if (entry.getKey().trim().equalsIgnoreCase(island) && entry.getValue() != null) {
						List<Tier> override = getter.apply(entry.getValue());
						if (override != null && !override.isEmpty()) {
							return override;
						}
					}
				}
			}
			return fallback;
		}

		/** The tier with the greatest {@code from} that is <= the total (inclusive); null for an empty ladder. */
		private static BigDecimal tierFee(List<Tier> ladder, BigDecimal itemsTotal) {
			if (ladder == null || ladder.isEmpty()) {
				return null;
			}
			Tier lowest = ladder.stream().min(Comparator.comparing(Tier::getFrom)).orElseThrow();
			return ladder.stream()
					.filter(t -> t.getFrom().compareTo(itemsTotal) <= 0)
					.max(Comparator.comparing(Tier::getFrom))
					.orElse(lowest)
					.getFee();
		}
	}
}
