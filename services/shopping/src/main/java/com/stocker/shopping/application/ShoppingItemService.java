package com.stocker.shopping.application;

import com.stocker.shopping.application.model.ComparisonRequest;
import com.stocker.shopping.application.model.ItemChange;
import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.PriceView;
import com.stocker.shopping.domain.ComparisonStatus;
import com.stocker.shopping.domain.ShoppingItemPrice;
import com.stocker.shopping.domain.ShoppingListItem;
import com.stocker.shopping.infrastructure.persistence.ShoppingItemPriceRepository;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shopping-list items and their price comparison. Adding an item only writes the row (status
 * PENDING) and publishes {@link ItemAddedEvent}; nothing here ever waits on pricing. Comparison
 * results arrive later through {@link #applyPrices}, from either the gRPC response or Kafka.
 */
@Service
public class ShoppingItemService {

	private static final String DEFAULT_CURRENCY = "NZD";

	private final ShoppingListItemRepository items;
	private final ShoppingItemPriceRepository prices;
	private final ApplicationEventPublisher events;
	private final Clock clock;

	public ShoppingItemService(ShoppingListItemRepository items, ShoppingItemPriceRepository prices,
			ApplicationEventPublisher events, Clock clock) {
		this.items = items;
		this.prices = prices;
		this.events = events;
		this.clock = clock;
	}

	@Transactional
	public ItemView addItem(AddItemCommand command) {
		ShoppingListItem item = ShoppingListItem.create(command.userId(), command.householdId(), command.name(),
				command.sku(), command.category(), command.region(), command.quantity(), now());
		items.save(item);
		events.publishEvent(new ItemAddedEvent(item.getId()));
		return toView(item, List.of());
	}

	@Transactional(readOnly = true)
	public List<ItemView> listForUser(String userId) {
		List<ShoppingListItem> found = items.findByUserIdOrderByCreatedAtDesc(userId);
		if (found.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<ShoppingItemPrice>> pricesByItem = prices
				.findByItemIdIn(found.stream().map(ShoppingListItem::getId).toList()).stream()
				.collect(Collectors.groupingBy(ShoppingItemPrice::getItemId));
		return found.stream().map(item -> toView(item, pricesByItem.getOrDefault(item.getId(), List.of()))).toList();
	}

	@Transactional(readOnly = true)
	public Optional<ItemView> findForUser(UUID itemId, String userId) {
		return items.findById(itemId)
				.filter(item -> item.getUserId().equals(userId))
				.map(item -> toView(item, prices.findByItemId(itemId)));
	}

	@Transactional(readOnly = true)
	public Optional<ComparisonRequest> loadForComparison(UUID itemId) {
		return items.findById(itemId)
				.map(item -> new ComparisonRequest(item.getId(), item.getName(), item.getCategory(), item.getRegion()));
	}

	/**
	 * Merges observed prices into the item, recomputes the cheapest and marks it AVAILABLE. Safe to
	 * call repeatedly and out of order: a price only replaces an older observation for the same
	 * (chain, store), so replays and duplicates change nothing. Returns the new state only if
	 * something a client would see actually changed; empty means "nothing to notify".
	 */
	@Transactional
	public Optional<ItemChange> applyPrices(UUID itemId, List<PricePoint> observed) {
		Optional<ShoppingListItem> found = items.findByIdForUpdate(itemId);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		ShoppingListItem item = found.get();
		OffsetDateTime now = now();

		Map<String, ShoppingItemPrice> stored = new HashMap<>();
		for (ShoppingItemPrice row : prices.findByItemId(itemId)) {
			stored.put(key(row.getChainId(), row.getStoreId()), row);
		}

		boolean pricesChanged = false;
		for (PricePoint point : observed) {
			if (!usable(point)) {
				continue;
			}
			String currency = point.currency() == null || point.currency().isBlank() ? DEFAULT_CURRENCY : point.currency();
			OffsetDateTime capturedAt = point.capturedAt() == null ? now : point.capturedAt();
			ShoppingItemPrice row = stored.get(key(point.chainId(), point.storeId()));
			if (row == null) {
				row = prices.save(ShoppingItemPrice.create(itemId, point.chainId(), point.storeId(),
						point.priceAmount(), currency, point.promoFlag(), capturedAt));
				stored.put(key(point.chainId(), point.storeId()), row);
				pricesChanged = true;
			} else if (capturedAt.isAfter(row.getCapturedAt())) {
				boolean differs = row.differsFrom(point.priceAmount(), currency, point.promoFlag());
				row.update(point.priceAmount(), currency, point.promoFlag(), capturedAt);
				prices.save(row);
				pricesChanged |= differs;
			}
		}

		if (stored.isEmpty()) {
			return Optional.empty();
		}
		ShoppingItemPrice cheapest = stored.values().stream().min(CHEAPEST_FIRST).orElseThrow();
		if (!pricesChanged && !item.cheapestDiffersFrom(cheapest)) {
			return Optional.empty();
		}
		item.applyCheapest(cheapest, now);
		items.save(item);
		return Optional.of(new ItemChange(item.getUserId(), toView(item, new ArrayList<>(stored.values()))));
	}

	/** Pricing rejected the request. Never overwrites an item that already has a comparison. */
	@Transactional
	public Optional<ItemChange> markUnavailable(UUID itemId) {
		Optional<ShoppingListItem> found = items.findByIdForUpdate(itemId);
		if (found.isEmpty() || !found.get().markUnavailable(now())) {
			return Optional.empty();
		}
		ShoppingListItem item = found.get();
		items.save(item);
		return Optional.of(new ItemChange(item.getUserId(), toView(item, prices.findByItemId(itemId))));
	}

	private static final Comparator<ShoppingItemPrice> CHEAPEST_FIRST = Comparator
			.comparing(ShoppingItemPrice::getPriceAmount)
			.thenComparing(ShoppingItemPrice::getChainId)
			.thenComparing(ShoppingItemPrice::getStoreId);

	private static boolean usable(PricePoint point) {
		return point != null
				&& point.priceAmount() != null
				&& point.priceAmount().signum() > 0
				&& point.chainId() != null && !point.chainId().isBlank()
				&& point.storeId() != null && !point.storeId().isBlank();
	}

	private static String key(String chainId, String storeId) {
		return chainId + "|" + storeId;
	}

	private OffsetDateTime now() {
		return OffsetDateTime.now(clock);
	}

	static ItemView toView(ShoppingListItem item, List<ShoppingItemPrice> rows) {
		List<PriceView> sorted = rows.stream()
				.sorted(CHEAPEST_FIRST)
				.map(row -> new PriceView(row.getChainId(), row.getStoreId(), row.getPriceAmount(), row.getCurrency(),
						row.isPromoFlag(), row.getCapturedAt()))
				.toList();
		PriceView cheapest = item.getComparisonStatus() == ComparisonStatus.AVAILABLE && !sorted.isEmpty()
				? sorted.get(0)
				: null;
		return new ItemView(item.getId(), item.getName(), item.getSku(), item.getCategory(), item.getRegion(),
				item.getQuantity(), item.getComparisonStatus().wire(), cheapest, sorted, item.getComparedAt(),
				item.getCreatedAt());
	}
}
