package com.stocker.pricing.service.match;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemMatchService.MatchResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemMatchServiceTest {

	private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

	private PriceRecordRepository repository;
	private PriceStatsService stats;
	private EventPublisher events;
	private IngestScheduler ingest;
	private ItemMatchService service;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		repository = mock(PriceRecordRepository.class);
		stats = mock(PriceStatsService.class);
		events = mock(EventPublisher.class);
		ingest = mock(IngestScheduler.class);
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(ingest);
		when(repository.save(any(PriceRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		service = new ItemMatchService(repository, stats, events, new PricingCacheProperties(), provider);
	}

	@Test
	void picksTheBestProductPerChainCheapestFirstAndLabelsTheRest() {
		when(repository.findIngestedSince(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW),
				row("newworld", "newworld:2", "Anchor Trim Milk 1L", "Anchor", "2.60", NOW),
				row("paknsave", "paknsave:1", "Pams Standard Milk 2L", "Pams", "3.90", NOW),
				row("woolworths", "woolworths:1", "Woolworths Full Cream Milk 2L", null, "4.10", NOW),
				row("paknsave", "paknsave:9", "Oat Drink 2L", null, "5.00", NOW)));

		MatchResult result = service.match("milk 2L", "list-item-1");

		assertEquals("lexical", result.matchMethod());
		assertEquals(List.of("PAKNSAVE", "WOOLWORTHS", "NEWWORLD"),
				result.matches().stream().map(m -> m.chainId()).toList());
		assertEquals("Anchor", result.matches().get(2).brand());
		assertEquals(1, result.alternatives().size(), "only the other-size New World milk; oat drink shares no word");
		assertEquals("Anchor Trim Milk 1L", result.alternatives().get(0).productName());
	}

	@Test
	void onlyTheChosenProductsAreStoredUnderTheCallersItemIdAndFeedTheStats() {
		when(repository.findIngestedSince(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW),
				row("newworld", "newworld:2", "Anchor Trim Milk 1L", "Anchor", "2.60", NOW)));

		service.match("milk 2L", "list-item-1");

		ArgumentCaptor<PriceRecord> saved = ArgumentCaptor.forClass(PriceRecord.class);
		verify(repository, times(1)).save(saved.capture());
		assertEquals("list-item-1", saved.getValue().getItemId());
		assertEquals(new BigDecimal("4.80"), saved.getValue().getPriceAmount());
		verify(stats, times(1)).recordObservation(any());
		verify(events, times(1)).publishPriceRecordCaptured(any());
	}

	@Test
	void askingAgainDoesNotStoreTheSameObservationTwice() {
		PriceRecord source = row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW);
		when(repository.findIngestedSince(any())).thenReturn(List.of(source));
		when(repository.findByItemId("list-item-1")).thenReturn(List.of(PriceRecord.builder()
				.itemId("list-item-1").chainId("newworld").storeId(source.getStoreId()).capturedAt(NOW).build()));

		service.match("milk 2L", "list-item-1");

		verify(repository, never()).save(any(PriceRecord.class));
	}

	@Test
	void aPackSizeScrapedIntoTheBrandSlotCountsAsPartOfTheName() {
		// New World / PAK'nSave: the card subtitle ("2l") is stored as the brand.
		when(repository.findIngestedSince(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Milk", "1l", "1.82", NOW),
				row("newworld", "newworld:2", "Anchor Blue Milk", "2l", "3.73", NOW)));

		MatchResult result = service.match("milk 2L", "list-item-1");

		assertEquals(1, result.matches().size());
		assertEquals("Anchor Blue Milk 2l", result.matches().get(0).productName());
		assertEquals(null, result.matches().get(0).brand(), "a size is not a brand");
		assertEquals(List.of("Anchor Blue Milk 1l"), result.alternatives().stream().map(a -> a.productName()).toList());
	}

	@Test
	void usesTheLatestScrapeOfEachProduct() {
		when(repository.findIngestedSince(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "5.50", NOW.minusDays(1)),
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW)));

		MatchResult result = service.match("milk 2L", "list-item-1");

		assertEquals(1, result.matches().size());
		assertEquals(new BigDecimal("4.80"), result.matches().get(0).priceAmount());
	}

	@Test
	void ignoresRowsWithoutANameAndUnknownChains() {
		when(repository.findIngestedSince(any())).thenReturn(List.of(
				row("newworld", "newworld:1", null, null, "4.80", NOW),
				row("spread-thing", "spread-thing:1", "Milk 2L", null, "3.00", NOW)));

		assertTrue(service.match("milk 2L", "list-item-1").matches().isEmpty());
	}

	@Test
	void nothingStoredStartsOneBackgroundIngestRunAndReturnsEmptyNotAnError() {
		when(repository.findIngestedSince(any())).thenReturn(List.of());

		MatchResult first = service.match("milk 2L", "list-item-1");
		service.match("milk 2L", "list-item-1");

		assertTrue(first.matches().isEmpty());
		assertTrue(first.alternatives().isEmpty());
		verify(ingest, timeout(2000).times(1)).runAll();
		verify(repository, never()).save(any(PriceRecord.class));
	}

	private static PriceRecord row(String chain, String itemId, String name, String brand, String price, OffsetDateTime at) {
		Map<String, Object> attributes = new java.util.HashMap<>();
		if (name != null) {
			attributes.put("name", name);
		}
		if (brand != null) {
			attributes.put("brand", brand);
		}
		return PriceRecord.builder().itemId(itemId).chainId(chain).storeId(chain + ":").channel("pickup")
				.priceAmount(new BigDecimal(price)).currency("NZD").capturedAt(at).createdAt(at)
				.rawAttributes(attributes).build();
	}
}
