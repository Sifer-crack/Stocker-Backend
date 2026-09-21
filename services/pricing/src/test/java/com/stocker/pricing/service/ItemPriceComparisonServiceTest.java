package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.service.ItemPriceComparisonService.ItemComparison;
import com.stocker.pricing.service.ItemPriceComparisonService.ItemQuery;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemPriceComparisonServiceTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);

	private PriceSearchService priceSearchService;
	private ItemPriceComparisonService service;

	@BeforeEach
	void setUp() {
		priceSearchService = mock(PriceSearchService.class);
		ServiceAreaProperties serviceArea = new ServiceAreaProperties();
		serviceArea.setSupportedRegions(List.of("auckland"));
		service = new ItemPriceComparisonService(priceSearchService, serviceArea);
	}

	@Test
	void returnsCheapestFirstAndKeepsTheFullSortedList() {
		when(priceSearchService.search("milk 2l", "item-1", List.of(), "milk")).thenReturn(List.of(
				record("newworld", "nw:1", "4.80", NOW),
				record("paknsave", "ps:1", "4.20", NOW),
				record("woolworths", "ww:1", "4.50", NOW)));

		ItemComparison result = service.compare(List.of(new ItemQuery("item-1", "milk 2l", "milk")), "").get(0);

		assertTrue(result.found());
		assertEquals("paknsave", result.cheapest().chainId());
		assertEquals("ps:1", result.cheapest().storeId());
		assertEquals(List.of("4.20", "4.50", "4.80"),
				result.prices().stream().map(p -> p.priceAmount().toPlainString()).toList());
	}

	@Test
	void keepsOnlyTheLatestPricePerStore() {
		when(priceSearchService.search("milk", "item-1", List.of(), null)).thenReturn(List.of(
				record("newworld", "nw:1", "5.00", NOW.minusDays(2)),
				record("newworld", "nw:1", "4.60", NOW),
				record("newworld", "nw:1", "4.90", NOW.minusDays(1))));

		ItemComparison result = service.compare(List.of(new ItemQuery("item-1", "milk", null)), "").get(0);

		assertEquals(1, result.prices().size());
		assertEquals(new BigDecimal("4.60"), result.cheapest().priceAmount());
	}

	@Test
	void ignoresZeroAndMissingPrices() {
		PriceRecord missing = record("woolworths", "ww:1", "1.00", NOW);
		missing.setPriceAmount(null);
		when(priceSearchService.search("milk", "item-1", List.of(), null)).thenReturn(List.of(
				record("newworld", "nw:1", "0.00", NOW), missing, record("paknsave", "ps:1", "4.20", NOW)));

		ItemComparison result = service.compare(List.of(new ItemQuery("item-1", "milk", null)), "").get(0);

		assertEquals(1, result.prices().size());
		assertEquals("paknsave", result.cheapest().chainId());
	}

	@Test
	void noRecordsIsNotFoundRatherThanAnError() {
		when(priceSearchService.search("milk", "item-1", List.of(), null)).thenReturn(List.of());

		ItemComparison result = service.compare(List.of(new ItemQuery("item-1", "milk", null)), "").get(0);

		assertFalse(result.found());
		assertNull(result.cheapest());
		assertTrue(result.prices().isEmpty());
	}

	@Test
	void oneItemFailingDoesNotFailTheOthers() {
		when(priceSearchService.search("bad", "item-1", List.of(), null)).thenThrow(new IllegalStateException("boom"));
		when(priceSearchService.search("milk", "item-2", List.of(), null))
				.thenReturn(List.of(record("paknsave", "ps:1", "4.20", NOW)));

		List<ItemComparison> results = service.compare(
				List.of(new ItemQuery("item-1", "bad", null), new ItemQuery("item-2", "milk", null)), "");

		assertFalse(results.get(0).found());
		assertTrue(results.get(1).found());
	}

	@Test
	void blankItemIdOrTermIsSkippedWithoutSearching() {
		List<ItemComparison> results = service.compare(
				List.of(new ItemQuery("", "milk", null), new ItemQuery("item-1", " ", null)), "");

		assertFalse(results.get(0).found());
		assertFalse(results.get(1).found());
		verify(priceSearchService, never()).search(any(), any(), any(), any());
	}

	@Test
	void unsupportedRegionIsRejectedButBlankRegionIsNot() {
		assertThrows(ServiceAreaException.class,
				() -> service.compare(List.of(new ItemQuery("item-1", "milk", null)), "mars"));
		service.compare(List.of(new ItemQuery("item-1", "milk", null)), "Auckland");
		service.compare(List.of(new ItemQuery("item-1", "milk", null)), "");
	}

	private static PriceRecord record(String chainId, String storeId, String price, OffsetDateTime capturedAt) {
		return PriceRecord.builder()
				.itemId("item-1")
				.chainId(chainId)
				.storeId(storeId)
				.channel("pickup")
				.priceAmount(new BigDecimal(price))
				.currency("NZD")
				.capturedAt(capturedAt)
				.createdAt(capturedAt)
				.build();
	}
}
