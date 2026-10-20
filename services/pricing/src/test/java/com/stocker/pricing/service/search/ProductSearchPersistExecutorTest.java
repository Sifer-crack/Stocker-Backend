package com.stocker.pricing.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The background store of matched prices is bounded: a slow database cannot queue work without limit. */
class ProductSearchPersistExecutorTest {

	@Test
	void whenTheQueueIsFullTheOldestWaitingStoreIsDroppedAndCounted() throws Exception {
		AtomicLong dropped = new AtomicLong();
		ThreadPoolExecutor executor = ProductSearchService.boundedPersistExecutor(3, dropped);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch running = new CountDownLatch(1);
		List<Integer> ran = Collections.synchronizedList(new ArrayList<>());
		executor.execute(() -> {
			running.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

		for (int i = 1; i <= 5; i++) {         // 3 fit in the queue; tasks 4 and 5 each push the oldest out
			int n = i;
			executor.execute(() -> ran.add(n));
		}
		release.countDown();
		executor.shutdown();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		assertThat(dropped.get()).isEqualTo(2);
		assertThat(ran).containsExactly(3, 4, 5);
	}

	@Test
	void theDefaultQueueHolds1000() {
		assertThat(ProductSearchService.PERSIST_QUEUE_CAPACITY).isEqualTo(1000);
	}
}
