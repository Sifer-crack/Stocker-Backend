package com.stocker.pricing.fetch.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.config.WebFetcherProperties;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

public class ScraperApiWebFetcher implements WebFetcher {

	private static final Logger log = LoggerFactory.getLogger(ScraperApiWebFetcher.class);

	private final WebClient webClient;
	private final WebFetcherProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public ScraperApiWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public List<RawProduct> fetch(WebFetchRequest request) {
		WebFetcherProperties.ScraperApiConfig config = properties.getScraperapi();
		if (config.getApiKey() == null || config.getApiKey().isBlank()) {
			log.warn("ScraperAPI key not configured, skipping fetch");
			return List.of();
		}

		List<String> urls = request.getStoreUrls() != null ? request.getStoreUrls() : List.of();
		if (urls.isEmpty()) {
			log.warn("ScraperAPI requires target URLs, none provided for query: {}", request.getSearchTerm());
			return List.of();
		}

		List<RawProduct> allProducts = new ArrayList<>();
		for (String targetUrl : urls) {
			URI uri = UriComponentsBuilder.fromUriString(config.getBaseUrl())
					.queryParam("api_key", config.getApiKey())
					.queryParam("url", targetUrl)
					.queryParam("render", "true")
					.queryParam("country_code", "nz")
					.build().toUri();

			log.info("ScraperAPI fetch: url={}", targetUrl);

			String body = webClient.get()
					.uri(uri)
					.header("Accept", "text/html,application/json")
					.retrieve()
					.bodyToMono(String.class)
					.block();

			allProducts.addAll(parseResponse(body, targetUrl, request));
		}

		return allProducts;
	}

	private List<RawProduct> parseResponse(String body, String sourceUrl, WebFetchRequest request) {
		List<RawProduct> products = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return products;
		}

		try {
			JsonNode root = objectMapper.readTree(body);
			JsonNode items = root.has("products") ? root.get("products") : null;
			if (items == null || !items.isArray()) {
				RawProduct fallback = RawProduct.builder()
						.name(request.getSearchTerm())
						.storeUrl(sourceUrl)
						.source(sourceUrl)
						.chainId("unknown")
						.rawResponse(Map.of("raw_html_length", body.length()))
						.fetchedAt(OffsetDateTime.now())
						.build();
				products.add(fallback);
				return products;
			}

			for (JsonNode item : items) {
				RawProduct product = RawProduct.builder()
						.name(getText(item, "name"))
						.brand(getText(item, "brand"))
						.price(getBigDecimal(item, "price"))
						.currency(getTextOrDefault(item, "currency", "NZD"))
						.storeUrl(getText(item, "url"))
						.imageUrl(getText(item, "image"))
						.source(sourceUrl)
						.chainId(extractChainId(sourceUrl))
						.rawResponse(toMap(item))
						.fetchedAt(OffsetDateTime.now())
						.build();
				products.add(product);
			}
		} catch (Exception e) {
			log.error("Failed to parse ScraperAPI response for {}", sourceUrl, e);
		}

		return products;
	}

	private String extractChainId(String url) {
		if (url == null) {
			return "unknown";
		}
		String lower = url.toLowerCase();
		if (lower.contains("paknsave")) {
			return "paknsave";
		}
		if (lower.contains("newworld")) {
			return "newworld";
		}
		if (lower.contains("woolworths") || lower.contains("countdown")) {
			return "woolworths";
		}
		if (lower.contains("foursquare")) {
			return "foursquare";
		}
		if (lower.contains("taiping")) {
			return "taiping";
		}
		if (lower.contains("limchhour")) {
			return "limchhour";
		}
		if (lower.contains("goldenresult")) {
			return "goldenresult";
		}
		return "unknown";
	}

	private String getText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() ? value.asText() : null;
	}

	private String getTextOrDefault(JsonNode node, String field, String defaultValue) {
		String value = getText(node, field);
		return value != null ? value : defaultValue;
	}

	private BigDecimal getBigDecimal(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		try {
			return new BigDecimal(value.asText());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Map<String, Object> toMap(JsonNode node) {
		try {
			return objectMapper.convertValue(node, HashMap.class);
		} catch (Exception e) {
			return Map.of();
		}
	}
}
