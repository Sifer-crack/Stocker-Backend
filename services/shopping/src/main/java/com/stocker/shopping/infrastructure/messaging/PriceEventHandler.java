package com.stocker.shopping.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.shopping.application.ComparisonUpdateService;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.UpdateSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Backfills comparisons from pricing's {@code stocker.pricing.events.v1} stream. Pricing publishes
 * {@code PriceRecordCaptured} keyed by the itemId it was asked about, which for items added here is
 * the shopping-list item's UUID. Anything else on the topic (ingest records keyed "chain:code",
 * unknown event types, malformed payloads) is ignored, never an error, so one bad message cannot
 * wedge the consumer. Idempotent: replays go through {@link ComparisonUpdateService}, whose
 * merge only ever accepts a newer observation and only notifies when something changed.
 */
@Component
public class PriceEventHandler {

	private static final Logger log = LoggerFactory.getLogger(PriceEventHandler.class);
	private static final String EVENT_TYPE = "PriceRecordCaptured";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ComparisonUpdateService updates;

	public PriceEventHandler(ComparisonUpdateService updates) {
		this.updates = updates;
	}

	public void handle(String payload) {
		JsonNode root;
		try {
			root = MAPPER.readTree(payload);
		} catch (Exception e) {
			log.warn("Ignoring unparseable pricing event: {}", e.getMessage());
			return;
		}
		if (root == null || !EVENT_TYPE.equals(text(root, "eventType"))) {
			return;
		}
		UUID itemId = toUuid(text(root, "itemId"));
		if (itemId == null) {
			return;
		}
		BigDecimal price = root.hasNonNull("priceAmount") ? root.get("priceAmount").decimalValue() : null;
		String chainId = text(root, "chainId");
		String storeId = text(root, "storeId");
		if (price == null || price.signum() <= 0 || chainId == null || storeId == null) {
			return;
		}
		PricePoint point = new PricePoint(chainId, storeId, price.setScale(2, RoundingMode.HALF_UP),
				text(root, "currency"), root.path("promoFlag").asBoolean(false), toTime(root.get("capturedAt")));
		updates.applyPrices(itemId, List.of(point), UpdateSource.BACKFILL);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.asText().isBlank()) {
			return null;
		}
		return value.asText();
	}

	private static UUID toUuid(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Pricing's Jackson 2 mapper writes OffsetDateTime as numeric epoch seconds (fractional) unless
	 * configured otherwise, but accept ISO-8601 text too so a serializer change can't break this.
	 */
	static OffsetDateTime toTime(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			BigDecimal seconds = node.decimalValue();
			long whole = seconds.setScale(0, RoundingMode.FLOOR).longValueExact();
			int nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).setScale(0, RoundingMode.HALF_UP).intValue();
			return OffsetDateTime.ofInstant(Instant.ofEpochSecond(whole, nanos), ZoneOffset.UTC);
		}
		try {
			return OffsetDateTime.parse(node.asText());
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
