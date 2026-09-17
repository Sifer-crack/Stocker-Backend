package com.stocker.pricing.service;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.model.PriceRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawProductPriceRecordMapperTest {

	@Test
	void mapsFullProduct() {
		OffsetDateTime fetchedAt = OffsetDateTime.now();
		RawProduct product = RawProduct.builder()
				.name("Blue Milk 2L")
				.brand("Anchor")
				.price(new BigDecimal("4.50"))
				.currency("NZD")
				.storeUrl("https://www.paknsave.co.nz/product/1")
				.chainId("paknsave")
				.rawResponse(Map.of("sku", "123"))
				.fetchedAt(fetchedAt)
				.build();

		PriceRecord record = RawProductPriceRecordMapper.toPriceRecord("item-1", product);

		assertEquals("item-1", record.getItemId());
		assertEquals("https://www.paknsave.co.nz/product/1", record.getStoreId());
		assertEquals("paknsave", record.getChainId());
		assertEquals("pickup", record.getChannel());
		assertEquals(new BigDecimal("4.50"), record.getPriceAmount());
		assertEquals("NZD", record.getCurrency());
		assertFalse(record.isPromoFlag());
		assertEquals(fetchedAt, record.getCapturedAt());
		assertEquals(Map.of("sku", "123"), record.getRawAttributes());
		assertTrue(record.getCreatedAt() != null);
	}

	@Test
	void appliesFallbacksWhenFieldsAreBlank() {
		RawProduct product = RawProduct.builder()
				.name("Milk")
				.price(null)
				.build();

		PriceRecord record = RawProductPriceRecordMapper.toPriceRecord("item-2", product);

		assertEquals("unknown", record.getStoreId());
		assertEquals("unknown", record.getChainId());
		assertEquals(BigDecimal.ZERO, record.getPriceAmount());
		assertEquals("NZD", record.getCurrency());
		assertEquals(Map.of(), record.getRawAttributes());
		assertTrue(record.getCapturedAt() != null);
	}

	@Test
	void derivesItemIdFromChainAndNativeProductCodeForIngestOverload() {
		RawProduct product = RawProduct.builder()
				.name("Blue Milk 2L")
				.price(new BigDecimal("4.50"))
				.chainId("paknsave")
				.nativeProductCode("P1234567")
				.build();

		PriceRecord record = RawProductPriceRecordMapper.toPriceRecord(product);

		assertEquals("paknsave:P1234567", record.getItemId());
	}

	@Test
	void ingestOverloadRejectsBlankNativeProductCode() {
		RawProduct product = RawProduct.builder()
				.name("Blue Milk 2L")
				.price(new BigDecimal("4.50"))
				.chainId("paknsave")
				.build();

		assertThrows(IllegalArgumentException.class, () -> RawProductPriceRecordMapper.toPriceRecord(product));
	}
}