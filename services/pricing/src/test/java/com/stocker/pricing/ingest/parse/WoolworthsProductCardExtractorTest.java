package com.stocker.pricing.ingest.parse;

import com.stocker.pricing.fetch.model.RawProduct;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WoolworthsProductCardExtractorTest {

	@Test
	void parsesFullCard() {
		ProductCardFields fields = new ProductCardFields(
				"Coca-Cola 2.25L", "Coca-Cola", "$4.99", "123456", "https://img.example/2.jpg", null);

		Optional<RawProduct> parsed = WoolworthsProductCardExtractor.parse(fields, "store-1");

		assertTrue(parsed.isPresent());
		RawProduct product = parsed.get();
		assertEquals("Coca-Cola 2.25L", product.getName());
		assertEquals(new BigDecimal("4.99"), product.getPrice());
		assertEquals("NZD", product.getCurrency());
		assertEquals("woolworths:store-1", product.getStoreUrl());
		assertEquals("woolworths", product.getChainId());
		assertEquals("123456", product.getNativeProductCode());
	}

	@Test
	void skipsCardWithNoStockcode() {
		ProductCardFields fields = new ProductCardFields("Coca-Cola 2.25L", null, "$4.99", null, null, null);

		assertTrue(WoolworthsProductCardExtractor.parse(fields, "store-1").isEmpty());
	}

	@Test
	void skipsCardWithUnparsablePrice() {
		ProductCardFields fields = new ProductCardFields("Coca-Cola 2.25L", null, "", "123456", null, null);

		assertTrue(WoolworthsProductCardExtractor.parse(fields, "store-1").isEmpty());
	}

	@Test
	void cleanNameStripsMinOrderNote() {
		assertEquals("Broccoli", WoolworthsProductCardExtractor.cleanName("Broccoli Min Order 2"));
	}

	@Test
	void cleanNameStripsMinimumParenthetical() {
		assertEquals("Bananas", WoolworthsProductCardExtractor.cleanName("Bananas (Minimum 3)"));
	}

	@Test
	void cleanNameStripsPerKgSuffix() {
		assertEquals("Beef Mince", WoolworthsProductCardExtractor.cleanName("Beef Mince per kg"));
	}

	@Test
	void cleanNameStripsWoolworthsBrandPrefix() {
		assertEquals("Full Cream Milk 2L", WoolworthsProductCardExtractor.cleanName("Woolworths Fresh Full Cream Milk 2L"));
		assertEquals("White Bread", WoolworthsProductCardExtractor.cleanName("Woolworths White Bread"));
	}
}
