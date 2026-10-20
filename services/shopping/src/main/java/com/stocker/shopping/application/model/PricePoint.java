package com.stocker.shopping.application.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One observed price for an item at a (chain, store); the input to a comparison, from gRPC or Kafka. */
public record PricePoint(String chainId, String storeId, BigDecimal priceAmount, String currency,
		boolean promoFlag, OffsetDateTime capturedAt) {
}
