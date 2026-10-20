package com.stocker.shopping.application;

import com.stocker.shopping.application.model.ItemChange;
import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.PriceView;
import com.stocker.shopping.application.model.UpdateSource;
import com.stocker.shopping.application.port.ComparisonNotifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ComparisonUpdateServiceTest {

	private final UUID itemId = UUID.randomUUID();
	private final List<PricePoint> prices = List.of();
	private ShoppingItemService items;
	private ComparisonNotifier notifier;
	private ComparisonUpdateService service;
	private ItemView view;

	@BeforeEach
	void setUp() {
		items = mock(ShoppingItemService.class);
		notifier = mock(ComparisonNotifier.class);
		service = new ComparisonUpdateService(items, notifier);
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);
		PriceView cheapest = new PriceView("paknsave", "ps:1", new BigDecimal("4.20"), "NZD", false, now);
		view = new ItemView(itemId, "milk", null, "milk", null, 1, "available", cheapest, List.of(cheapest), now, now);
	}

	@Test
	void syncAndBackfillUseTheSameNotifierAndCarryTheFullItemState() {
		when(items.applyPrices(itemId, prices)).thenReturn(Optional.of(new ItemChange("user-1", view)));

		service.applyPrices(itemId, prices, UpdateSource.SYNC);
		service.applyPrices(itemId, prices, UpdateSource.BACKFILL);

		verify(notifier).itemUpdated("user-1", UpdateSource.SYNC, view);
		verify(notifier).itemUpdated("user-1", UpdateSource.BACKFILL, view);
	}

	@Test
	void nothingIsPushedWhenNothingChanged() {
		when(items.applyPrices(itemId, prices)).thenReturn(Optional.empty());

		service.applyPrices(itemId, prices, UpdateSource.BACKFILL);

		verify(notifier, never()).itemUpdated(any(), any(), any());
	}

	@Test
	void anUnavailableItemIsAnnouncedThroughTheSameNotifier() {
		when(items.markUnavailable(itemId)).thenReturn(Optional.of(new ItemChange("user-1", view)));

		service.markUnavailable(itemId, UpdateSource.SYNC);

		verify(notifier).itemUpdated("user-1", UpdateSource.SYNC, view);
	}
}
