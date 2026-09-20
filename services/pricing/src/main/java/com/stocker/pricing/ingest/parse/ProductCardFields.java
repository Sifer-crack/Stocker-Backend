package com.stocker.pricing.ingest.parse;

/** Raw strings pulled off one product card's DOM, before any parsing/normalization. */
public record ProductCardFields(
		String rawName,
		String rawBrand,
		String rawPriceText,
		String nativeProductCode,
		String imageUrl,
		String category) {
}
