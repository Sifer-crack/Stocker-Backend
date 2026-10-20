package com.stocker.shopping.integration;

import com.stocker.shopping.application.AddItemCommand;
import com.stocker.shopping.application.ComparisonUpdateService;
import com.stocker.shopping.application.ShoppingItemService;
import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.model.UpdateSource;
import com.stocker.shopping.application.port.ComparisonNotifier;
import com.stocker.shopping.application.port.PriceComparisonClient;
import com.stocker.shopping.infrastructure.persistence.ShoppingItemPriceRepository;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Runs the comparison-update path against a REAL PostgreSQL: the V3 migration must match the JPA
 * entities (ddl-auto=validate), and the row lock must serialize concurrent updates. Skipped unless
 * STOCKER_SHOPPING_DB_IT=true; it uses the service's default datasource (localhost:5432/shopping,
 * stocker/stocker), e.g. a throwaway `docker run postgres` container. Kafka is not started.
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_SHOPPING_DB_IT", matches = "true")
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class ShoppingItemPostgresIT {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private ShoppingItemService service;
	@Autowired
	private ComparisonUpdateService updates;
	@Autowired
	private ShoppingListItemRepository items;
	@Autowired
	private ShoppingItemPriceRepository prices;

	@MockitoBean
	private ComparisonNotifier notifier;
	@MockitoBean
	private PriceComparisonClient pricing;

	@Test
	void backfillBuildsTheComparisonIdempotentlyAndIgnoresStaleData() {
		String user = "it-" + UUID.randomUUID();
		UUID id = service.addItem(new AddItemCommand(user, null, "milk", null, "milk", null, 1)).id();
		try {
			assertEquals("pending", service.findForUser(id, user).orElseThrow().comparisonStatus());

			updates.applyPrices(id, List.of(point("newworld", "nw:1", "4.80", T0)), UpdateSource.BACKFILL);
			updates.applyPrices(id, List.of(point("paknsave", "ps:1", "4.20", T0)), UpdateSource.BACKFILL);
			updates.applyPrices(id, List.of(point("paknsave", "ps:1", "4.20", T0)), UpdateSource.BACKFILL); // replay
			updates.applyPrices(id, List.of(point("paknsave", "ps:1", "1.00", T0.minusHours(2))), UpdateSource.BACKFILL); // stale

			ItemView view = service.findForUser(id, user).orElseThrow();
			assertEquals("available", view.comparisonStatus());
			assertEquals("paknsave", view.cheapest().chainId());
			assertEquals(new BigDecimal("4.20"), view.cheapest().priceAmount());
			assertEquals(2, view.prices().size());
			verify(notifier, times(2)).itemUpdated(eq(user), eq(UpdateSource.BACKFILL), any());

			updates.applyPrices(id, List.of(point("paknsave", "ps:1", "3.90", T0.plusHours(1))), UpdateSource.SYNC);
			assertEquals(new BigDecimal("3.90"), service.findForUser(id, user).orElseThrow().cheapest().priceAmount());
		} finally {
			items.deleteById(id);
		}
		assertTrue(prices.findByItemId(id).isEmpty(), "prices must cascade-delete with the item");
	}

	@Test
	void concurrentUpdatesForTheSameItemAreSerializedByTheRowLock() throws Exception {
		String user = "it-" + UUID.randomUUID();
		UUID id = service.addItem(new AddItemCommand(user, null, "tim tam", null, "biscuit", null, 1)).id();
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					updates.applyPrices(id, List.of(point("paknsave", "ps:9", "5.00", T0)), UpdateSource.BACKFILL);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> future : futures) {
				future.get(30, TimeUnit.SECONDS); // any unique-constraint or lock failure surfaces here
			}

			assertEquals(1, prices.findByItemId(id).size());
			assertEquals("available", service.findForUser(id, user).orElseThrow().comparisonStatus());
			verify(notifier, times(1)).itemUpdated(eq(user), eq(UpdateSource.BACKFILL), any());
		} finally {
			pool.shutdownNow();
			items.deleteById(id);
		}
	}

	private static PricePoint point(String chain, String store, String price, OffsetDateTime capturedAt) {
		return new PricePoint(chain, store, new BigDecimal(price), "NZD", false, capturedAt);
	}
}
