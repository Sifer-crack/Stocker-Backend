package com.stocker.pricing.fetch.impl;

import com.stocker.pricing.fetch.config.WebFetcherProperties;
import com.stocker.pricing.fetch.model.RawProduct;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidApiWebFetcherTest {

	private final WebFetcherProperties.RapidApiConfig config = new WebFetcherProperties.RapidApiConfig() {{
		setHost("woolworths-products-api.p.rapidapi.com");
	}};

	@Test
	void parsesRealProductSearchResponse() {
		String body = """
				{"query":"timtam","results":[
				  {"barcode":9310072034942,"product_name":"Arnott's TimTams Inspired By Jatz",
				   "product_brand":"Arnott's","current_price":6.0,"product_size":"165g",
				   "url":"https://www.woolworths.com.au/shop/productdetails/904881"}],
				 "total_results":1,"total_pages":1,"current_page":1}
				""";

		List<RawProduct> products = RapidApiWebFetcher.parseResponse(body, config);

		assertEquals(1, products.size());
		RawProduct p = products.get(0);
		assertEquals("Arnott's TimTams Inspired By Jatz", p.getName());
		assertEquals("Arnott's", p.getBrand());
		assertEquals(6.0, p.getPrice().doubleValue());
		assertEquals("AUD", p.getCurrency());
		assertEquals("https://www.woolworths.com.au/shop/productdetails/904881", p.getStoreUrl());
		assertEquals("woolworths", p.getChainId());
		assertEquals("woolworths", p.getSource());
	}

	@Test
	void parsesRealPriceChangesResponse() {
		String body = """
				{"results":[
				  {"barcode":9300675087018,"product_name":"Fanta Orange Zero Sugar Soft Drink",
				   "product_brand":"Fanta","old_price":4.2,"new_price":2.5,
				   "url":"https://www.woolworths.com.au/shop/productdetails/6049732"}],
				 "total_results":1,"current_page":1,"page_size":1}
				""";

		List<RawProduct> products = RapidApiWebFetcher.parseResponse(body, config);

		assertEquals(1, products.size());
		RawProduct p = products.get(0);
		assertEquals("Fanta Orange Zero Sugar Soft Drink", p.getName());
		assertEquals("Fanta", p.getBrand());
		assertEquals(2.5, p.getPrice().doubleValue());
		assertEquals("AUD", p.getCurrency());
		assertEquals(4.2, ((Number) p.getRawResponse().get("old_price")).doubleValue());
	}

	@Test
	void parsesProductsArray() {
		String body = """
				[{"name": "Tim Tam Original","price": 5.49,"currency": "AUD",
				  "url": "https://woolworths.co.nz/tim-tam","image": "http://img/t1"}]
				""";

		List<RawProduct> products = RapidApiWebFetcher.parseResponse(body, config);

		assertEquals(1, products.size());
		RawProduct p = products.get(0);
		assertEquals("Tim Tam Original", p.getName());
		assertEquals(5.49, p.getPrice().doubleValue());
		assertEquals("AUD", p.getCurrency());
		assertEquals("https://woolworths.co.nz/tim-tam", p.getStoreUrl());
		assertEquals("http://img/t1", p.getImageUrl());
		assertEquals("woolworths", p.getChainId());
	}

	@Test
	void parsesWrappedProductsNode() {
		String body = """
				{"products": [{"title": "Coca-Cola 2.25L","extracted_price": "4.69",
				  "link": "https://woolworths.co.nz/coke"}]}
				""";

		List<RawProduct> products = RapidApiWebFetcher.parseResponse(body, config);

		assertEquals(1, products.size());
		assertEquals("Coca-Cola 2.25L", products.get(0).getName());
		assertEquals(4.69, products.get(0).getPrice().doubleValue());
		assertEquals("https://woolworths.co.nz/coke", products.get(0).getStoreUrl());
		assertEquals("AUD", products.get(0).getCurrency());
	}

	@Test
	void returnsEmptyForEmptyOrGarbageBody() {
		assertTrue(RapidApiWebFetcher.parseResponse("", config).isEmpty());
		assertTrue(RapidApiWebFetcher.parseResponse("   ", config).isEmpty());
		assertTrue(RapidApiWebFetcher.parseResponse("not json {", config).isEmpty());
		assertTrue(RapidApiWebFetcher.parseResponse("[{}]", config).isEmpty());
	}

	@Test
	void derivesChainIdFromHost() {
		WebFetcherProperties.RapidApiConfig ge = new WebFetcherProperties.RapidApiConfig();
		ge.setHost("woolworths-nz.p.rapidapi.com");
		assertEquals("woolworths", RapidApiWebFetcher
				.parseResponse("[{\"name\":\"Copper Kettle\"}]", ge).get(0).getChainId());

		WebFetcherProperties.RapidApiConfig unknown = new WebFetcherProperties.RapidApiConfig();
		unknown.setHost("some-api.p.rapidapi.com");
		assertEquals("someapi", RapidApiWebFetcher
				.parseResponse("[{\"name\":\"x\"}]", unknown).get(0).getChainId());
	}

	@Test
	void fallsBackToRapidapiWithoutHost() {
		WebFetcherProperties.RapidApiConfig blank = new WebFetcherProperties.RapidApiConfig();
		blank.setHost("");
		assertEquals("rapidapi", RapidApiWebFetcher
				.parseResponse("[{\"name\":\"x\"}]", blank).get(0).getChainId());
	}

	@Test
	void missingPriceIsNull() {
		List<RawProduct> products = RapidApiWebFetcher.parseResponse(
				"[{\"name\":\"Milk\"}]", config);
		assertNull(products.get(0).getPrice());
	}
}