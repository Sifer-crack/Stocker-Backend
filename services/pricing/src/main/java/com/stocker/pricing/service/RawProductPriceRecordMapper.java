package com.stocker.pricing.service;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.model.PriceRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

public final class RawProductPriceRecordMapper {

	private static final String DEFAULT_CHANNEL = "pickup";
	private static final String DEFAULT_CURRENCY = "NZD";
	private static final String UNKNOWN_CHAIN = "unknown";

	private RawProductPriceRecordMapper() {
	}

	public static PriceRecord toPriceRecord(String itemId, RawProduct product) {
		String chainId = blankTo(product.getChainId(), UNKNOWN_CHAIN);
		return PriceRecord.builder()
				.itemId(itemId)
				.storeId(blankTo(product.getStoreUrl(), chainId))
				.chainId(chainId)
				.channel(DEFAULT_CHANNEL)
				.priceAmount(product.getPrice() != null ? product.getPrice() : BigDecimal.ZERO)
				.currency(blankTo(product.getCurrency(), DEFAULT_CURRENCY))
				.promoFlag(false)
				.capturedAt(product.getFetchedAt() != null ? product.getFetchedAt() : OffsetDateTime.now(ZoneOffset.UTC))
				.rawAttributes(product.getRawResponse() != null ? product.getRawResponse() : Map.of())
				.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
				.build();
	}

	/** For batch-ingested products with no caller-supplied itemId; derives one from the chain-native product code. */
	public static PriceRecord toPriceRecord(RawProduct product) {
		return toPriceRecord(deriveIngestItemId(product), product);
	}

	private static String deriveIngestItemId(RawProduct product) {
		String chainId = blankTo(product.getChainId(), UNKNOWN_CHAIN);
		String nativeCode = product.getNativeProductCode();
		if (nativeCode == null || nativeCode.isBlank()) {
			throw new IllegalArgumentException(
					"nativeProductCode required to derive ingest itemId (chainId=" + chainId + ")");
		}
		return chainId + ":" + nativeCode;
	}

	private static String blankTo(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}