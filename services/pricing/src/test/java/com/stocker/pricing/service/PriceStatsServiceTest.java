package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceStatsRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceStatsServiceTest {

	private PriceStatsRepository repository;
	private PriceStatsService service;

	@BeforeEach
	void setUp() {
		repository = mock(PriceStatsRepository.class);
		service = new PriceStatsService(repository);
	}

	@Test
	void firstObservationCreatesStatsWithAllPricesEqualToIt() {
		when(repository.findByItemIdAndStoreId("item-1", "store-1")).thenReturn(Optional.empty());

		service.recordObservation(record("item-1", "store-1", "3.79"));

		PriceStats saved = capture();
		assertEquals(new BigDecimal("3.79"), saved.getCurrentPrice());
		assertEquals(new BigDecimal("3.79"), saved.getLowestPrice());
		assertEquals(new BigDecimal("3.79"), saved.getHighestPrice());
		assertEquals(new BigDecimal("3.79"), saved.getMedianPrice());
		assertEquals(1, saved.getObservationCount());
		assertEquals(1, saved.getPriceHistory().size());
	}

	@Test
	void secondObservationUpdatesCurrentLowestHighestAndOddMedian() {
		PriceStats existing = existingStats("item-1", "store-1", "3.79");
		when(repository.findByItemIdAndStoreId("item-1", "store-1")).thenReturn(Optional.of(existing));

		service.recordObservation(record("item-1", "store-1", "4.29"));

		PriceStats saved = capture();
		assertEquals(new BigDecimal("4.29"), saved.getCurrentPrice(), "current price is always the latest observation");
		assertEquals(new BigDecimal("3.79"), saved.getLowestPrice());
		assertEquals(new BigDecimal("4.29"), saved.getHighestPrice());
		assertEquals(2, saved.getObservationCount());
	}

	@Test
	void evenNumberOfObservationsAveragesTheTwoMiddlePrices() {
		PriceStats existing = existingStats("item-1", "store-1", "3.00", "5.00");
		when(repository.findByItemIdAndStoreId("item-1", "store-1")).thenReturn(Optional.of(existing));

		service.recordObservation(record("item-1", "store-1", "4.00"));

		PriceStats saved = capture();
		// sorted: 3.00, 4.00, 5.00 (odd count of 3) -> median is the middle one.
		assertEquals(new BigDecimal("4.00"), saved.getMedianPrice());
	}

	@Test
	void handlesHistoryEntriesReadBackAsDoubleAfterAJsonRoundTrip() {
		// Simulates re-loading a row from the DB: Hibernate's JSON deserialization turns numeric
		// values into Double, not BigDecimal, inside the untyped Map<String, Object> history entries.
		PriceStats existing = new PriceStats();
		existing.setItemId("item-1");
		existing.setStoreId("store-1");
		existing.setPriceHistory(new ArrayList<>(List.of(observationAsDouble(3.79))));
		when(repository.findByItemIdAndStoreId("item-1", "store-1")).thenReturn(Optional.of(existing));

		service.recordObservation(record("item-1", "store-1", "4.29"));

		PriceStats saved = capture();
		assertEquals(new BigDecimal("3.79"), saved.getLowestPrice());
		assertEquals(new BigDecimal("4.29"), saved.getHighestPrice());
	}

	private PriceStats capture() {
		ArgumentCaptor<PriceStats> captor = ArgumentCaptor.forClass(PriceStats.class);
		verify(repository).save(captor.capture());
		return captor.getValue();
	}

	private static PriceRecord record(String itemId, String storeId, String price) {
		return PriceRecord.builder()
				.itemId(itemId)
				.storeId(storeId)
				.chainId("newworld")
				.currency("NZD")
				.priceAmount(new BigDecimal(price))
				.capturedAt(OffsetDateTime.now(ZoneOffset.UTC))
				.build();
	}

	private static PriceStats existingStats(String itemId, String storeId, String... priorPrices) {
		List<Map<String, Object>> history = new ArrayList<>();
		for (String price : priorPrices) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("price", new BigDecimal(price));
			entry.put("capturedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
			history.add(entry);
		}
		PriceStats stats = new PriceStats();
		stats.setItemId(itemId);
		stats.setStoreId(storeId);
		stats.setPriceHistory(history);
		return stats;
	}

	private static Map<String, Object> observationAsDouble(double price) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("price", price);
		entry.put("capturedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
		return entry;
	}
}
