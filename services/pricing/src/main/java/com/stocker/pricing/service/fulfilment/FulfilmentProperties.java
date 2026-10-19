package com.stocker.pricing.service.fulfilment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-chain travel / delivery / click-and-collect costs. Values are data (application.yml), never code. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.fulfilment")
public class FulfilmentProperties {

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

	@Getter
	@Setter
	public static class ChainFees {
		/** Cost of going to the store; 0 unless configured. */
		private BigDecimal travelCost = BigDecimal.ZERO;
		/** Null = delivery not offered. */
		private BigDecimal deliveryFee;
		/** Null = click and collect not offered. */
		private BigDecimal clickAndCollectFee;
		/** Optional: delivery costs 0 when the items total is at least this. */
		private BigDecimal deliveryFreeOver;
	}
}
