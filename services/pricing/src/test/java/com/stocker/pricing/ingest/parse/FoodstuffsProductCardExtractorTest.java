package com.stocker.pricing.ingest.parse;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodstuffsProductCardExtractorTest {

	@Test
	void parsesFullCard() {
		// rawPriceText here matches the real primary source: meta[itemprop=price]'s content
		// attribute, a clean decimal with no "$" - see FoodstuffsChainScraper.
		ProductCardFields fields = new ProductCardFields(
				"Anchor Blue Milk", "1l", "3.73", "5000518", "https://img.example/1.jpg", null);

		Optional<RawProduct> parsed = FoodstuffsProductCardExtractor.parse(fields, ChainId.PAKNSAVE, "store-1");

		assertTrue(parsed.isPresent());
		RawProduct product = parsed.get();
		assertEquals("Anchor Blue Milk", product.getName());
		assertEquals("1l", product.getBrand());
		assertEquals(new BigDecimal("3.73"), product.getPrice());
		assertEquals("NZD", product.getCurrency());
		assertEquals("paknsave:store-1", product.getStoreUrl());
		assertEquals("paknsave", product.getChainId());
		assertEquals("5000518", product.getNativeProductCode());
	}

	@Test
	void parsesCleanDecimalPriceDirectly() {
		assertEquals(new BigDecimal("3.73"), FoodstuffsProductCardExtractor.parsePrice("3.73"));
	}

	@Test
	void skipsCardWithNoName() {
		ProductCardFields fields = new ProductCardFields(null, null, "$4.50", "P1234567", null, null);

		assertTrue(FoodstuffsProductCardExtractor.parse(fields, ChainId.NEWWORLD, "store-1").isEmpty());
	}

	@Test
	void skipsCardWithNoNativeProductCode() {
		ProductCardFields fields = new ProductCardFields("Blue Milk 2L", null, "$4.50", null, null, null);

		assertTrue(FoodstuffsProductCardExtractor.parse(fields, ChainId.NEWWORLD, "store-1").isEmpty());
	}

	@Test
	void skipsCardWithUnparsablePrice() {
		ProductCardFields fields = new ProductCardFields("Blue Milk 2L", null, "each", "P1234567", null, null);

		assertTrue(FoodstuffsProductCardExtractor.parse(fields, ChainId.NEWWORLD, "store-1").isEmpty());
	}

	@Test
	void parsesPriceWithSpaceAndNoDecimals() {
		assertEquals(new BigDecimal("12"), FoodstuffsProductCardExtractor.parsePrice("$ 12"));
	}
}
