package com.stocker.pricing.service.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Choosing a different product for an item replaces the price stored for the earlier one (so compare uses the new one). */
class ItemPriceStoreReplaceTest {

	private static final OffsetDateTime EARLIER = OffsetDateTime.now(ZoneOffset.UTC).minusHours(5);
	private static final OffsetDateTime LATER = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);

	private PriceRecordRepository records;
	private PriceStatsService stats;
	private ItemPriceStore store;

	@BeforeEach
	void setUp() {
		records = mock(PriceRecordRepository.class);
		stats = mock(PriceStatsService.class);
		store = new ItemPriceStore(records, stats, mock(EventPublisher.class));
		when(records.save(any(PriceRecord.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	private static NormalizedProduct source(String chain, String name, String price, OffsetDateTime at) {
		return NormalizedProduct.builder().itemId(chain + ":" + name).storeId(chain + ":").chainId(chain)
				.displayName(name).priceAmount(new BigDecimal(price)).currency("NZD").capturedAt(at).build();
	}

	private static PriceRecord stored(String chain, OffsetDateTime at) {
		return PriceRecord.builder().itemId("item-1").storeId(chain + ":").chainId(chain).capturedAt(at)
				.priceAmount(BigDecimal.ONE).build();
	}

	@Test
	void anItemWithAnEarlierProductHasItDroppedBeforeTheNewOneIsStored() {
		when(records.findByItemId("item-1")).thenReturn(List.of(stored("paknsave", LATER)));

		store.replace("item-1", List.of(source("paknsave", "Other Wine 500ml", "9.99", EARLIER)));

		InOrder order = inOrder(records, stats);
		order.verify(records).deleteByItemId("item-1");
		order.verify(stats).clearItem("item-1");
		order.verify(records).save(any(PriceRecord.class));
		order.verify(stats).recordObservation(any(PriceRecord.class));
	}

	@Test
	void replacingWithTheSameObservationsChangesNothing() {
		when(records.findByItemId("item-1")).thenReturn(List.of(stored("paknsave", LATER)));

		store.replace("item-1", List.of(source("paknsave", "Same Wine 500ml", "2.50", LATER)));

		verify(records, never()).deleteByItemId(any());
		verify(stats, never()).clearItem(any());
		verify(records, never()).save(any(PriceRecord.class));
	}

	@Test
	void anItemWithNothingStoredJustStoresWithoutDeleting() {
		when(records.findByItemId("item-1")).thenReturn(List.of());

		store.replace("item-1", List.of(source("paknsave", "New Wine 500ml", "2.50", LATER)));

		verify(records, never()).deleteByItemId(any());
		assertThat(mockingSaves()).isTrue();
	}

	private boolean mockingSaves() {
		verify(records).save(any(PriceRecord.class));
		return true;
	}
}
