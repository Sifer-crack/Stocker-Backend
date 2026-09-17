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

public class RapidApiWebFetcher implements WebFetcher {

	private static final Logger log = LoggerFactory.getLogger(RapidApiWebFetcher.class);

	private final WebClient webClient;
	private final WebFetcherProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public RapidApiWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		this.webClient = webClient;
		this.properties = properties;
	}

	@Override
	public List<RawProduct> fetch(WebFetchRequest request) {
		WebFetcherProperties.RapidApiConfig config = properties.getRapidapi();
		if (!isConfigured(config)) {
			log.warn("RapidAPI host, api key or base url not configured, skipping fetch");
			return List.of();
		}

		URI uri = UriComponentsBuilder.fromUriString(config.getBaseUrl())
				.path(config.getEndpoint())
				.queryParam(config.getQueryParam(), request.getSearchTerm())
				.queryParam("page_size", config.getLimit())
				.build().toUri();

		log.info("RapidAPI fetch: host={}, q={}", config.getHost(), request.getSearchTerm());

		String body = webClient.get()
				.uri(uri)
				.header("x-rapidapi-host", config.getHost())
				.header("x-rapidapi-key", config.getApiKey())
				.header("Accept", "application/json")
				.retrieve()
				.bodyToMono(String.class)
				.block();

		return parseResponse(body, config);
	}

	public List<RawProduct> fetchPriceChanges(int page, int pageSize, String date) {
		WebFetcherProperties.RapidApiConfig config = properties.getRapidapi();
		if (!isConfigured(config)) {
			log.warn("RapidAPI host, api key or base url not configured, skipping price changes");
			return List.of();
		}

		URI uri = UriComponentsBuilder.fromUriString(config.getBaseUrl())
				.path(config.getPriceChangesEndpoint())
				.queryParam("page", page)
				.queryParam("page_size", pageSize)
				.queryParam("date", date)
				.build().toUri();

		log.info("RapidAPI price changes: host={}, page={}, date={}", config.getHost(), page, date);

		String body = webClient.get()
				.uri(uri)
				.header("x-rapidapi-host", config.getHost())
				.header("x-rapidapi-key", config.getApiKey())
				.header("Accept", "application/json")
				.retrieve()
				.bodyToMono(String.class)
				.block();

		return parseResponse(body, config);
	}

	private static boolean isConfigured(WebFetcherProperties.RapidApiConfig config) {
		return config.getApiKey() != null && !config.getApiKey().isBlank()
				&& config.getHost() != null && !config.getHost().isBlank()
				&& config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
	}

	static List<RawProduct> parseResponse(String body, WebFetcherProperties.RapidApiConfig config) {
		List<RawProduct> products = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return products;
		}

		try {
			JsonNode root = new ObjectMapper().readTree(body);
			JsonNode items = extractArray(root);

			if (items != null) {
				for (JsonNode item : items) {
					String name = getText(item, "name", "title", "product_name", "productName");
					BigDecimal price = getBigDecimal(item, "price", "current_price", "new_price",
							"price_amount", "extracted_price");
					if (name == null && price == null) {
						continue;
					}
					RawProduct product = RawProduct.builder()
							.name(name)
							.brand(getText(item, "brand", "product_brand", "seller", "merchant"))
							.price(price)
							.currency(getTextOrDefault(item, "currency",
									config.getCurrency() == null || config.getCurrency().isBlank()
											? "AUD" : config.getCurrency()))
							.storeUrl(getText(item, "url", "link", "product_url", "href"))
							.imageUrl(getText(item, "image", "image_url", "thumbnail", "product_image"))
							.source(chainId(config.getHost()))
							.chainId(chainId(config.getHost()))
							.category(getText(item, "category", "department"))
							.rawResponse(toMap(item))
							.fetchedAt(OffsetDateTime.now())
							.build();
					products.add(product);
				}
			}
		} catch (Exception e) {
			log.error("Failed to parse RapidAPI response", e);
		}

		return products;
	}

	private static JsonNode extractArray(JsonNode root) {
		if (root.isArray()) {
			return root;
		}
		for (String field : List.of("products", "items", "results", "data", "records")) {
			JsonNode node = root.get(field);
			if (node != null && node.isArray()) {
				return node;
			}
		}
		return null;
	}

	private static String getText(JsonNode node, String... fields) {
		for (String field : fields) {
			JsonNode value = node.get(field);
			if (value != null && !value.isNull()) {
				return value.asText();
			}
		}
		return null;
	}

	private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
		String value = getText(node, field);
		return value != null ? value : defaultValue;
	}

	private static BigDecimal getBigDecimal(JsonNode node, String... fields) {
		for (String field : fields) {
			JsonNode value = node.get(field);
			if (value == null || value.isNull()) {
				continue;
			}
			if (value.isNumber()) {
				return value.decimalValue();
			}
			try {
				return new BigDecimal(value.asText());
			} catch (NumberFormatException ignored) {
				// try next field
			}
		}
		return null;
	}

	private static Map<String, Object> toMap(JsonNode node) {
		try {
			return new ObjectMapper().convertValue(node, HashMap.class);
		} catch (Exception e) {
			return Map.of();
		}
	}

	private static String chainId(String host) {
		if (host == null || host.isBlank()) {
			return "rapidapi";
		}
		String lower = host.toLowerCase();
		if (lower.contains("woolworths") || lower.contains("countdown")) {
			return "woolworths";
		}
		return lower.replaceAll("\\..*", "").replaceAll("[^a-z0-9]", "");
	}
}