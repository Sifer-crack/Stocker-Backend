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

public class SerpApiWebFetcher implements WebFetcher {

	private static final Logger log = LoggerFactory.getLogger(SerpApiWebFetcher.class);

	private final WebClient webClient;
	private final WebFetcherProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public SerpApiWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public List<RawProduct> fetch(WebFetchRequest request) {
		WebFetcherProperties.SerpApiConfig config = properties.getSerpapi();
		if (config.getApiKey() == null || config.getApiKey().isBlank()) {
			log.warn("SerpApi key not configured, skipping fetch");
			return List.of();
		}

		URI uri = UriComponentsBuilder.fromUriString(config.getBaseUrl())
				.queryParam("engine", "google_shopping")
				.queryParam("q", request.getSearchTerm())
				.queryParam("gl", config.getCountry())
				.queryParam("hl", "en")
				.queryParam("api_key", config.getApiKey())
				.build().toUri();

		log.info("SerpApi fetch: q={}, gl={}", request.getSearchTerm(), config.getCountry());

		String body = webClient.get()
				.uri(uri)
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
			JsonNode shoppingResults = root.get("shopping_results");
			if (shoppingResults == null || !shoppingResults.isArray()) {
				return products;
			}

			for (JsonNode item : shoppingResults) {
				RawProduct product = RawProduct.builder()
						.name(getText(item, "title"))
						.brand(getText(item, "source"))
						.price(getBigDecimal(item, "extracted_price"))
						.currency("NZD")
						.storeUrl(getText(item, "link"))
						.imageUrl(getText(item, "thumbnail"))
						.source(getText(item, "source"))
						.chainId(extractChainId(getText(item, "source")))
						.rawResponse(toMap(item))
						.fetchedAt(OffsetDateTime.now())
						.build();
				products.add(product);
			}
		} catch (Exception e) {
			log.error("Failed to parse SerpApi response", e);
		}

		return products;
	}

	private String extractChainId(String source) {
		if (source == null) {
			return "unknown";
		}
		String lower = source.toLowerCase();
		if (lower.contains("pak'nsave") || lower.contains("pak nsave") || lower.contains("paknsave")) {
			return "paknsave";
		}
		if (lower.contains("new world")) {
			return "newworld";
		}
		if (lower.contains("woolworths") || lower.contains("countdown")) {
			return "woolworths";
		}
		if (lower.contains("four square")) {
			return "foursquare";
		}
		return source.toLowerCase().replaceAll("[^a-z0-9]", "");
	}

	private String getText(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && !value.isNull() ? value.asText() : null;
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
