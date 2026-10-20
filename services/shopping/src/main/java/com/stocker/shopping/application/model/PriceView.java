package com.stocker.shopping.application.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PriceView(String chainId, String storeId, BigDecimal priceAmount, String currency,
		boolean promoFlag, OffsetDateTime capturedAt) {
}
