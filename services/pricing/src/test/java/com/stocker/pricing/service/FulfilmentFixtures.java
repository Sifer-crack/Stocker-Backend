package com.stocker.pricing.service;

import com.stocker.pricing.service.fulfilment.FulfilmentProperties;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.ChainFees;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.IslandFees;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.Tier;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds fee configuration for tests. Numbers in tests are examples only, never real supermarket fees. */
public final class FulfilmentFixtures {

	private FulfilmentFixtures() {
	}

	/** A ladder from (from, fee) pairs given as strings: tiers("0", "14.00", "200", "9.00"). */
	public static List<Tier> tiers(String... fromFeePairs) {
		List<Tier> tiers = new ArrayList<>();
		for (int i = 0; i < fromFeePairs.length; i += 2) {
			tiers.add(new Tier(new BigDecimal(fromFeePairs[i]), new BigDecimal(fromFeePairs[i + 1])));
		}
		return tiers;
	}

	/**
	 * Flat-fee shorthand: any argument may be null (= not offered / no free threshold). A delivery
	 * fee becomes the single tier [0 -> fee]; a free-over threshold adds [threshold -> 0].
	 */
	public static ChainFees fees(String delivery, String clickAndCollect, String deliveryFreeOver) {
		ChainFees fees = new ChainFees();
		if (delivery != null) {
			fees.setDelivery(deliveryFreeOver == null ? tiers("0", delivery) : tiers("0", delivery, deliveryFreeOver, "0"));
		}
		if (clickAndCollect != null) {
			fees.setClickAndCollect(tiers("0", clickAndCollect));
		}
		return fees;
	}

	/** Tiered fees; a null ladder = method not offered. */
	public static ChainFees tiered(List<Tier> delivery, List<Tier> clickAndCollect) {
		ChainFees fees = new ChainFees();
		fees.setDelivery(delivery);
		fees.setClickAndCollect(clickAndCollect);
		return fees;
	}

	/** Island override for a chain; either ladder may be null (= use the chain default). */
	public static void island(ChainFees fees, String island, List<Tier> delivery, List<Tier> clickAndCollect) {
		IslandFees override = new IslandFees();
		override.setDelivery(delivery);
		override.setClickAndCollect(clickAndCollect);
		fees.getIslands().put(island, override);
	}

	public static FulfilmentProperties properties(Map<String, ChainFees> chains) {
		FulfilmentProperties properties = new FulfilmentProperties();
		properties.setChains(new LinkedHashMap<>(chains));
		return properties;
	}

	/**
	 * The reference fixture of docs/features/fulfilment-costs.md: the shipped (north island) ladders. PAK'nSAVE offers
	 * only click and collect.
	 */
	public static FulfilmentProperties mixedReference() {
		return properties(Map.of(
				"paknsave", tiered(null, tiers("0", "5.00")),
				"woolworths", tiered(tiers("0", "14.00", "200", "9.00"), tiers("0", "3.50")),
				"newworld", tiered(tiers("0", "14.00", "200", "9.00"), tiers("0", "5.00", "50", "0.00"))));
	}
}
