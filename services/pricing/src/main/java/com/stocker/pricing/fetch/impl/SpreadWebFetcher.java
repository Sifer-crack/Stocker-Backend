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

public class SpreadWebFetcher implements WebFetcher {

	private static final Logger log = LoggerFactory.getLogger(SpreadWebFetcher.class);

	private final WebClient webClient;
	private final WebFetcherProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public SpreadWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public List<RawProduct> fetch(WebFetchRequest request) {
		WebFetcherProperties.SpreadConfig config = properties.getSpread();
		if (config.getApiKey() == null || config.getApiKey().isBlank()) {
			log.warn("Spread API key not configured, skipping fetch");
			return List.of();
		}

		URI uri = UriComponentsBuilder.fromUriString(config.getBaseUrl())
				.path("/products/search")
				.queryParam("q", request.getSearchTerm())
				.queryParam("limit", 50)
				.build().toUri();

		log.info("Spread fetch: q={}", request.getSearchTerm());

		String body = webClient.get()
				.uri(uri)
				.header("Authorization", "Bearer " + config.getApiKey())
				.header("Accept", "application/json")
				.retrieve()
				.bodyToMono(String.class)
				.block();

		return parseResponse(body, request);
	}

	private List<RawProduct> parseResponse(String body, WebFetchRequest request) {
		List<RawProduct> products = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return products;
		}

		try {
			JsonNode root = objectMapper.readTree(body);
			JsonNode items = root.has("products") ? root.get("products") : root;
			if (!items.isArray()) {
				return products;
			}

			for (JsonNode item : items) {
				RawProduct product = RawProduct.builder()
						.name(getText(item, "name"))
						.brand(getText(item, "brand"))
						.price(getBigDecimal(item, "price"))
						.currency(getTextOrDefault(item, "currency", "NZD"))
						.storeUrl(getText(item, "url"))
						.imageUrl(getText(item, "image_url"))
						.source(getText(item, "store"))
						.chainId(getText(item, "chain"))
						.category(getText(item, "category"))
						.rawResponse(toMap(item))
						.fetchedAt(OffsetDateTime.now())
						.build();
				products.add(product);
			}
		} catch (Exception e) {
			log.error("Failed to parse Spread response", e);
		}

		return products;
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
