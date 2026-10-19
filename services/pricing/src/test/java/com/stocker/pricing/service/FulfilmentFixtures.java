package com.stocker.pricing.service;

import com.stocker.pricing.service.fulfilment.FulfilmentProperties;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.ChainFees;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds fee configuration for tests. Numbers in tests are examples only, never real supermarket fees. */
public final class FulfilmentFixtures {

	private FulfilmentFixtures() {
	}

	/** Any argument may be null (= not offered / no free threshold). Travel null = 0. */
	public static ChainFees fees(String travel, String delivery, String clickAndCollect, String deliveryFreeOver) {
		ChainFees fees = new ChainFees();
		if (travel != null) {
			fees.setTravelCost(new BigDecimal(travel));
		}
		fees.setDeliveryFee(delivery == null ? null : new BigDecimal(delivery));
		fees.setClickAndCollectFee(clickAndCollect == null ? null : new BigDecimal(clickAndCollect));
		fees.setDeliveryFreeOver(deliveryFreeOver == null ? null : new BigDecimal(deliveryFreeOver));
		return fees;
	}

	public static FulfilmentProperties properties(Map<String, ChainFees> chains) {
		FulfilmentProperties properties = new FulfilmentProperties();
		properties.setChains(new LinkedHashMap<>(chains));
		return properties;
	}

	/** The mixed reference fixture of docs/features/fulfilment-costs.md. */
	public static FulfilmentProperties mixedReference() {
		return properties(Map.of(
				"paknsave", fees("4.00", "9.00", "3.00", null),
				"woolworths", fees("0.50", "6.00", "4.50", "20.00"),
				"newworld", fees("0.00", "5.00", "0.00", null)));
	}
}
