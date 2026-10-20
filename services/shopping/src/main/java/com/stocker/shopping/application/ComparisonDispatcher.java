package com.stocker.shopping.application;

import com.stocker.shopping.application.model.ComparisonRequest;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.UpdateSource;
import com.stocker.shopping.application.port.PriceComparisonClient;
import com.stocker.shopping.application.port.PricingRejectedException;
import com.stocker.shopping.application.port.PricingUnavailableException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The fast-path half of add-item. Once the add-item transaction has committed, hands the
 * comparison to a background executor so the HTTP response never waits on pricing. One attempt,
 * one deadline (set on the gRPC client), no retries: if pricing fails or is slow the item simply
 * stays PENDING and the Kafka backfill completes it when pricing publishes the prices.
 */
@Component
public class ComparisonDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ComparisonDispatcher.class);

	private final ShoppingItemService items;
	private final PriceComparisonClient pricing;
	private final ComparisonUpdateService updates;
	private final Executor executor;

	public ComparisonDispatcher(ShoppingItemService items, PriceComparisonClient pricing,
			ComparisonUpdateService updates, @Qualifier("comparisonExecutor") Executor executor) {
		this.items = items;
		this.pricing = pricing;
		this.updates = updates;
		this.executor = executor;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onItemAdded(ItemAddedEvent event) {
		try {
			executor.execute(() -> compare(event.itemId()));
		} catch (RejectedExecutionException e) {
			// Never let this surface to the add-item caller; the backfill still covers the item.
			log.warn("Comparison queue full; itemId={} stays pending until pricing events backfill it", event.itemId());
		}
	}

	void compare(UUID itemId) {
		try {
			Optional<ComparisonRequest> request = items.loadForComparison(itemId);
			if (request.isEmpty()) {
				return;
			}
			ComparisonRequest r = request.get();
			List<PricePoint> prices;
			try {
				prices = pricing.compare(itemId.toString(), r.searchTerm(), r.category(), r.region());
			} catch (PricingRejectedException e) {
				log.info("Pricing rejected itemId={}: {}", itemId, e.getMessage());
				updates.markUnavailable(itemId, UpdateSource.SYNC);
				return;
			} catch (PricingUnavailableException e) {
				log.warn("Pricing unavailable for itemId={} ({}); item stays pending", itemId, e.getMessage());
				return;
			}
			if (prices.isEmpty()) {
				log.info("No prices yet for itemId={}; item stays pending until pricing events arrive", itemId);
				return;
			}
			updates.applyPrices(itemId, prices, UpdateSource.SYNC);
		} catch (RuntimeException e) {
			log.error("Comparison failed for itemId={}; item stays pending", itemId, e);
		}
	}
}
