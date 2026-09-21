package com.stocker.shopping.application;

import com.stocker.shopping.application.model.ComparisonRequest;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.UpdateSource;
import com.stocker.shopping.application.port.PriceComparisonClient;
import com.stocker.shopping.application.port.PricingRejectedException;
import com.stocker.shopping.application.port.PricingUnavailableException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComparisonDispatcherTest {

	private final UUID itemId = UUID.randomUUID();
	private ShoppingItemService items;
	private PriceComparisonClient pricing;
	private ComparisonUpdateService updates;
	private ComparisonDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		items = mock(ShoppingItemService.class);
		pricing = mock(PriceComparisonClient.class);
		updates = mock(ComparisonUpdateService.class);
		dispatcher = new ComparisonDispatcher(items, pricing, updates, Runnable::run);
		when(items.loadForComparison(itemId))
				.thenReturn(Optional.of(new ComparisonRequest(itemId, "full cream milk 2L", "milk", "auckland")));
	}

	@Test
	void successfulComparisonIsAppliedAsTheSyncSource() {
		List<PricePoint> prices = List.of(new PricePoint("paknsave", "ps:1", new BigDecimal("4.20"), "NZD", false,
				OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC)));
		when(pricing.compare(itemId.toString(), "full cream milk 2L", "milk", "auckland")).thenReturn(prices);

		dispatcher.onItemAdded(new ItemAddedEvent(itemId));

		verify(updates).applyPrices(itemId, prices, UpdateSource.SYNC);
	}

	@Test
	void noPricesYetLeavesTheItemPendingForTheBackfill() {
		when(pricing.compare(any(), any(), any(), any())).thenReturn(List.of());

		dispatcher.onItemAdded(new ItemAddedEvent(itemId));

		verify(updates, never()).applyPrices(any(), any(), any());
		verify(updates, never()).markUnavailable(any(), any());
	}

	@Test
	void pricingFailureOrTimeoutLeavesTheItemPendingAndIsAttemptedExactlyOnce() {
		when(pricing.compare(any(), any(), any(), any()))
				.thenThrow(new PricingUnavailableException("DEADLINE_EXCEEDED", new RuntimeException()));

		assertDoesNotThrow(() -> dispatcher.onItemAdded(new ItemAddedEvent(itemId)));

		verify(pricing, times(1)).compare(any(), any(), any(), any());
		verify(updates, never()).applyPrices(any(), any(), any());
		verify(updates, never()).markUnavailable(any(), any());
	}

	@Test
	void pricingRejectingTheRequestMarksTheItemUnavailable() {
		when(pricing.compare(any(), any(), any(), any())).thenThrow(new PricingRejectedException("outside service area"));

		dispatcher.onItemAdded(new ItemAddedEvent(itemId));

		verify(updates).markUnavailable(itemId, UpdateSource.SYNC);
	}

	@Test
	void anUnexpectedErrorIsContainedAndDoesNotEscape() {
		when(pricing.compare(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

		assertDoesNotThrow(() -> dispatcher.onItemAdded(new ItemAddedEvent(itemId)));
		verify(updates, never()).applyPrices(any(), any(), any());
	}

	@Test
	void aFullQueueNeverFailsTheAddItemCaller() {
		ComparisonDispatcher saturated = new ComparisonDispatcher(items, pricing, updates, task -> {
			throw new RejectedExecutionException("queue full");
		});

		assertDoesNotThrow(() -> saturated.onItemAdded(new ItemAddedEvent(itemId)));
		verify(pricing, never()).compare(any(), any(), any(), any());
	}

	@Test
	void anItemThatNoLongerExistsIsSkippedWithoutCallingPricing() {
		UUID gone = UUID.randomUUID();
		when(items.loadForComparison(gone)).thenReturn(Optional.empty());

		dispatcher.onItemAdded(new ItemAddedEvent(gone));

		verify(pricing, never()).compare(eq(gone.toString()), any(), any(), any());
	}
}
