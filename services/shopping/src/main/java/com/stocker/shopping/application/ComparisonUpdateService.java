package com.stocker.shopping.application;

import com.stocker.shopping.application.model.ItemChange;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.UpdateSource;
import com.stocker.shopping.application.port.ComparisonNotifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The single place a comparison update is persisted and announced. The synchronous gRPC result
 * ({@link UpdateSource#SYNC}) and the Kafka backfill ({@link UpdateSource#BACKFILL}) both come
 * through here, so they cannot drift apart in what they store or what the gateway is told.
 * Deliberately not transactional: the notification must go out only after the write has committed.
 */
@Service
public class ComparisonUpdateService {

	private final ShoppingItemService items;
	private final ComparisonNotifier notifier;

	public ComparisonUpdateService(ShoppingItemService items, ComparisonNotifier notifier) {
		this.items = items;
		this.notifier = notifier;
	}

	public void applyPrices(UUID itemId, List<PricePoint> prices, UpdateSource source) {
		announce(items.applyPrices(itemId, prices), source);
	}

	public void markUnavailable(UUID itemId, UpdateSource source) {
		announce(items.markUnavailable(itemId), source);
	}

	private void announce(Optional<ItemChange> change, UpdateSource source) {
		change.ifPresent(c -> notifier.itemUpdated(c.userId(), source, c.item()));
	}
}
