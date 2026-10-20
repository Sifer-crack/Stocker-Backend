package com.stocker.pricing.service;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.model.PriceRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
				.rawAttributes(rawAttributes(product))
				.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
				.build();
	}

	/**
	 * The provider's raw payload plus the product's identifying fields, so a stored price can always
	 * say WHICH product it is (ingest has no raw payload at all). Provider keys are never overwritten.
	 */
	static Map<String, Object> rawAttributes(RawProduct product) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		if (product.getRawResponse() != null) {
			attributes.putAll(product.getRawResponse());
		}
		putIfPresent(attributes, "name", product.getName());
		putIfPresent(attributes, "brand", product.getBrand());
		putIfPresent(attributes, "nativeProductCode", product.getNativeProductCode());
		putIfPresent(attributes, "storeUrl", product.getStoreUrl());
		putIfPresent(attributes, "imageUrl", product.getImageUrl());
		putIfPresent(attributes, "category", product.getCategory());
		return attributes;
	}

	private static void putIfPresent(Map<String, Object> attributes, String key, String value) {
		if (value != null && !value.isBlank()) {
			attributes.putIfAbsent(key, value);
		}
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