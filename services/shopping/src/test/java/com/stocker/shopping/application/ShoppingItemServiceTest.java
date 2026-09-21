package com.stocker.shopping.application;

import com.stocker.shopping.application.model.ItemChange;
import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.domain.ShoppingItemPrice;
import com.stocker.shopping.domain.ShoppingListItem;
import com.stocker.shopping.infrastructure.persistence.ShoppingItemPriceRepository;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingItemServiceTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);

	private ShoppingListItemRepository items;
	private ShoppingItemPriceRepository prices;
	private ApplicationEventPublisher events;
	private ShoppingItemService service;
	private final List<ShoppingItemPrice> priceTable = new ArrayList<>();
	private ShoppingListItem item;

	@BeforeEach
	void setUp() {
		items = mock(ShoppingListItemRepository.class);
		prices = mock(ShoppingItemPriceRepository.class);
		events = mock(ApplicationEventPublisher.class);
		service = new ShoppingItemService(items, prices, events, Clock.fixed(Instant.parse("2026-09-21T13:00:00Z"), ZoneOffset.UTC));

		item = ShoppingListItem.create("user-1", null, "full cream milk 2L", null, "milk", null, 1, T0);
		when(items.findByIdForUpdate(item.getId())).thenReturn(Optional.of(item));
		when(prices.findByItemId(item.getId())).thenAnswer(inv -> new ArrayList<>(priceTable));
		when(prices.save(any(ShoppingItemPrice.class))).thenAnswer(inv -> {
			ShoppingItemPrice saved = inv.getArgument(0);
			if (!priceTable.contains(saved)) {
				priceTable.add(saved);
			}
			return saved;
		});
	}

	@Test
	void addItemSavesAPendingItemAndPublishesTheEventWithoutAnyPricing() {
		ItemView view = service.addItem(new AddItemCommand("user-1", null, "milk", null, "milk", "auckland", 2));

		assertEquals("pending", view.comparisonStatus());
		assertNull(view.cheapest());
		assertTrue(view.prices().isEmpty());
		ArgumentCaptor<ShoppingListItem> saved = ArgumentCaptor.forClass(ShoppingListItem.class);
		verify(items).save(saved.capture());
		assertEquals("user-1", saved.getValue().getUserId());
		ArgumentCaptor<ItemAddedEvent> event = ArgumentCaptor.forClass(ItemAddedEvent.class);
		verify(events).publishEvent(event.capture());
		assertEquals(view.id(), event.getValue().itemId());
	}

	@Test
	void applyPricesStoresEveryPriceAndMarksTheCheapestAvailable() {
		ItemChange change = service.applyPrices(item.getId(), List.of(
				point("newworld", "nw:1", "4.80", T0), point("paknsave", "ps:1", "4.20", T0),
				point("woolworths", "ww:1", "4.50", T0))).orElseThrow();

		ItemView view = change.item();
		assertEquals("user-1", change.userId());
		assertEquals("available", view.comparisonStatus());
		assertEquals("paknsave", view.cheapest().chainId());
		assertEquals("ps:1", view.cheapest().storeId());
		assertEquals(new BigDecimal("4.20"), view.cheapest().priceAmount());
		assertEquals(List.of("paknsave", "woolworths", "newworld"), view.prices().stream().map(p -> p.chainId()).toList());
	}

	@Test
	void applyingTheSamePricesAgainChangesNothingSoNothingIsNotified() {
		List<PricePoint> observed = List.of(point("paknsave", "ps:1", "4.20", T0));
		assertTrue(service.applyPrices(item.getId(), observed).isPresent());

		assertTrue(service.applyPrices(item.getId(), observed).isEmpty());
		assertEquals(1, priceTable.size());
	}

	@Test
	void anOlderObservationNeverReplacesANewerOne() {
		service.applyPrices(item.getId(), List.of(point("paknsave", "ps:1", "4.20", T0)));

		Optional<ItemChange> change = service.applyPrices(item.getId(), List.of(point("paknsave", "ps:1", "3.00", T0.minusHours(1))));

		assertTrue(change.isEmpty());
		assertEquals(new BigDecimal("4.20"), item.getCheapestPrice());
	}

	@Test
	void aNewerObservationUpdatesThePriceAndTheCheapest() {
		service.applyPrices(item.getId(), List.of(point("paknsave", "ps:1", "4.20", T0)));

		ItemChange change = service.applyPrices(item.getId(), List.of(point("paknsave", "ps:1", "3.90", T0.plusHours(1)))).orElseThrow();

		assertEquals(new BigDecimal("3.90"), change.item().cheapest().priceAmount());
		assertEquals(1, change.item().prices().size());
	}

	@Test
	void backfillArrivingOnePriceAtATimeBuildsTheFullComparison() {
		ItemChange first = service.applyPrices(item.getId(), List.of(point("newworld", "nw:1", "4.80", T0))).orElseThrow();
		assertEquals("newworld", first.item().cheapest().chainId());

		ItemChange second = service.applyPrices(item.getId(), List.of(point("paknsave", "ps:1", "4.20", T0))).orElseThrow();

		assertEquals("paknsave", second.item().cheapest().chainId());
		assertEquals(2, second.item().prices().size());
	}

	@Test
	void unusablePricesAreIgnoredAndTheItemStaysPending() {
		Optional<ItemChange> change = service.applyPrices(item.getId(), List.of(
				point("newworld", "nw:1", "0.00", T0), point("", "x", "3.00", T0), point("paknsave", " ", "3.00", T0),
				new PricePoint("woolworths", "ww:1", null, "NZD", false, T0)));

		assertTrue(change.isEmpty());
		assertEquals("pending", ShoppingItemService.toView(item, List.of()).comparisonStatus());
	}

	@Test
	void unknownItemIsIgnored() {
		assertTrue(service.applyPrices(UUID.randomUUID(), List.of(point("newworld", "nw:1", "4.80", T0))).isEmpty());
	}

	@Test
	void markUnavailableMovesAPendingItemButNeverOverwritesAStoredComparison() {
		ItemChange rejected = service.markUnavailable(item.getId()).orElseThrow();
		assertEquals("unavailable", rejected.item().comparisonStatus());

		ShoppingListItem compared = ShoppingListItem.create("user-1", null, "tim tam", null, "biscuit", null, 1, T0);
		when(items.findByIdForUpdate(compared.getId())).thenReturn(Optional.of(compared));
		when(prices.findByItemId(compared.getId())).thenReturn(List.of());
		service.applyPrices(compared.getId(), List.of(point("newworld", "nw:9", "5.00", T0)));

		assertTrue(service.markUnavailable(compared.getId()).isEmpty());
		assertEquals("available", ShoppingItemService.toView(compared, List.of()).comparisonStatus());
	}

	private static PricePoint point(String chain, String store, String price, OffsetDateTime capturedAt) {
		return new PricePoint(chain, store, new BigDecimal(price), "NZD", false, capturedAt);
	}
}
