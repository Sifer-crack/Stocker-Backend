package com.stocker.shopping.infrastructure.messaging;

import com.stocker.shopping.application.ComparisonUpdateService;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.UpdateSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PriceEventHandlerTest {

	private final UUID itemId = UUID.randomUUID();
	private ComparisonUpdateService updates;
	private PriceEventHandler handler;

	@BeforeEach
	void setUp() {
		updates = mock(ComparisonUpdateService.class);
		handler = new PriceEventHandler(updates);
	}

	@Test
	void aPriceRecordCapturedEventBackfillsTheItemAsTheBackfillSource() {
		handler.handle(event(itemId.toString(), "\"paknsave\"", "\"ps:1\"", "4.2", "true", "\"2026-09-21T12:00:00Z\""));

		PricePoint point = captured();
		assertEquals("paknsave", point.chainId());
		assertEquals("ps:1", point.storeId());
		assertEquals(new BigDecimal("4.20"), point.priceAmount());
		assertEquals("NZD", point.currency());
		assertTrue(point.promoFlag());
		assertEquals(java.time.OffsetDateTime.parse("2026-09-21T12:00:00Z").toInstant(), point.capturedAt().toInstant());
	}

	@Test
	void pricingsNumericEpochSecondsTimestampIsUnderstood() {
		// What pricing's Jackson 2 mapper actually writes for an OffsetDateTime by default.
		handler.handle(event(itemId.toString(), "\"paknsave\"", "\"ps:1\"", "4.2", "false", "1758456000.500000000"));

		PricePoint point = captured();
		assertEquals(1758456000L, point.capturedAt().toEpochSecond());
		assertEquals(500_000_000, point.capturedAt().getNano());
	}

	@Test
	void ingestRecordsKeyedByChainAndCodeAreIgnored() {
		handler.handle(event("newworld:P123", "\"newworld\"", "\"nw:1\"", "4.2", "false", "1758456000.0"));

		verify(updates, never()).applyPrices(any(), any(), any());
	}

	@Test
	void malformedJsonAndOtherEventTypesAreIgnoredWithoutThrowing() {
		handler.handle("not json {");
		handler.handle("");
		handler.handle("{\"eventType\":\"SomethingElse\",\"itemId\":\"" + itemId + "\"}");

		verify(updates, never()).applyPrices(any(), any(), any());
	}

	@Test
	void eventsWithoutAUsablePriceOrStoreAreIgnored() {
		handler.handle(event(itemId.toString(), "\"paknsave\"", "\"ps:1\"", "0", "false", "1758456000.0"));
		handler.handle(event(itemId.toString(), "\"paknsave\"", "\"ps:1\"", "null", "false", "1758456000.0"));
		handler.handle(event(itemId.toString(), "\"paknsave\"", "null", "4.2", "false", "1758456000.0"));
		handler.handle(event(itemId.toString(), "null", "\"ps:1\"", "4.2", "false", "1758456000.0"));

		verify(updates, never()).applyPrices(any(), any(), any());
	}

	private PricePoint captured() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<PricePoint>> points = ArgumentCaptor.forClass(List.class);
		verify(updates).applyPrices(org.mockito.ArgumentMatchers.eq(itemId), points.capture(), org.mockito.ArgumentMatchers.eq(UpdateSource.BACKFILL));
		assertEquals(1, points.getValue().size());
		return points.getValue().get(0);
	}

	private static String event(String itemId, String chain, String store, String price, String promo, String capturedAt) {
		return "{\"eventType\":\"PriceRecordCaptured\",\"id\":\"r1\",\"itemId\":\"" + itemId + "\","
				+ "\"storeId\":" + store + ",\"chainId\":" + chain + ",\"channel\":\"pickup\",\"priceAmount\":" + price
				+ ",\"currency\":\"NZD\",\"promoFlag\":" + promo + ",\"capturedAt\":" + capturedAt + "}";
	}
}
