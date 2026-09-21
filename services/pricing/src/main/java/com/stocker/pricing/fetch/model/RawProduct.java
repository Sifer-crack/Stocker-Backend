package com.stocker.pricing.fetch.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
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
public class RawProduct {

	private String name;
	private String brand;
	private BigDecimal price;
	private String currency;
	private String storeUrl;
	private String imageUrl;
	private String source;
	private String chainId;
	private String category;
	private Map<String, Object> rawResponse;
	private OffsetDateTime fetchedAt;

	/** Chain-native product code (e.g. Foodstuffs "P1234567", Woolworths NZ stockcode). */
	private String nativeProductCode;
}
