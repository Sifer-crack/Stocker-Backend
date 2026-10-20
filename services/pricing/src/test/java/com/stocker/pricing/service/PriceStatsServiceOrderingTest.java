package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Every price write also refreshes normalized_products. That second write must start only after the price_stats
 * transaction has finished, so one write never holds two database connections at once (with a small Neon pool,
 * concurrent writers each waiting for a second connection stall until the timeout).
 */
class PriceStatsServiceOrderingTest {

	private PriceStatsWriter writer;
	private NormalizedProductService normalized;
	private PriceStatsService service;
	private final PriceRecord record = PriceRecord.builder().itemId("paknsave:1").storeId("paknsave:").chainId("paknsave")
			.channel("pickup").priceAmount(new BigDecimal("4.20")).currency("NZD")
			.capturedAt(OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC)).rawAttributes(Map.of("name", "Milk")).build();

	@BeforeEach
	void setUp() {
		writer = mock(PriceStatsWriter.class);
		normalized = mock(NormalizedProductService.class);
		service = new PriceStatsService(writer, mock(PriceBatchWriter.class), normalized);
	}

	@Test
	void theNormalisedRefreshRunsOnlyAfterTheStatsWriteReturned() {
		service.recordObservation(record);

		InOrder order = inOrder(writer, normalized);
		order.verify(writer).record(record);
		order.verify(normalized).observe(record);
	}

	@Test
	void aFailingRefreshNeverFailsTheWrite() {
		doThrow(new IllegalStateException("db down")).when(normalized).observe(any());

		service.recordObservation(record);   // must not throw

		verify(writer).record(record);
	}

	@Test
	void aFailingStatsWriteIsNotHiddenAndSkipsTheRefresh() {
		doThrow(new IllegalStateException("stats failed")).when(writer).record(any());

		assertThatThrownBy(() -> service.recordObservation(record)).isInstanceOf(IllegalStateException.class);
		verify(normalized, never()).observe(any());
	}
}
