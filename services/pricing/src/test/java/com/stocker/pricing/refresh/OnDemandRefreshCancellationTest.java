package com.stocker.pricing.refresh;

import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import io.grpc.Context;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refreshes started by a request that is later cancelled (the gRPC call's Context) must not load supermarket pages
 * that have not started, and must not leave the cooldown / hourly cap charged for them. A call that simply completes
 * (gRPC then ends the Context WITHOUT a cause) must not stop the background refresh it started.
 */
class OnDemandRefreshCancellationTest {

	private NormalizedProductRepository repository;
	private IngestScheduler scheduler;
	private PricingCacheProperties properties;
	private OnDemandRefreshService service;
	private final CommonProduct milk = CommonProductCatalog.loadDefault().byKey("milk").orElseThrow();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		scheduler = mock(IngestScheduler.class);
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(scheduler);
		properties = new PricingCacheProperties();
		properties.getSnapshot().setTtl(Duration.ZERO);
		properties.getOnDemand().setCategoryCooldown(Duration.ofHours(1));
		properties.getOnDemand().setMaxPageLoadsPerChainPerHour(1);
		OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
		// only New World is stale, so each request wants exactly one page
		OffsetDateTime fresh = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
		when(repository.newestByChain("milk")).thenReturn(List.of(
				new Object[] { "newworld", old }, new Object[] { "paknsave", fresh }, new Object[] { "woolworths", fresh }));
		service = new OnDemandRefreshService(new NormalizedProductSnapshot(repository, properties),
				CategoryMap.loadDefault(), CategoryRouter.loadDefault(), properties, provider);
	}

	private void ensureFreshIn(Context context, Runnable followUp) {
		context.run(() -> service.ensureFresh(milk, followUp));
	}

	@Test
	void aRequestAlreadyCancelledLoadsNothingAndKeepsTheBookkeepingClean() {
		Context.CancellableContext cancelled = Context.current().withCancellation();
		cancelled.cancel(new RuntimeException("client gone"));
		AtomicInteger followUps = new AtomicInteger();

		ensureFreshIn(cancelled, followUps::incrementAndGet);

		verify(scheduler, never()).refreshCategories(any(), anyList(), any());
		verify(scheduler, never()).refreshCategories(any(), anyList());
		// cooldown and hourly cap (1 per hour) were not charged: the next, live request is allowed to load the page
		ensureFreshIn(Context.current().withCancellation(), () -> { });
		verify(scheduler, timeout(2_000).times(1)).refreshCategories(eq(ChainId.NEWWORLD), anyList(), any());
		assertThat(followUps).hasValue(0);
	}

	@Test
	void aJobQueuedBehindAnotherPageLoadDoesNotStartOnceItsRequestIsCancelled() throws Exception {
		properties.getOnDemand().setMaxPageLoadsPerChainPerHour(2);
		properties.getOnDemand().setCategoryCooldown(Duration.ZERO);
		CountDownLatch firstLoading = new CountDownLatch(1);
		CountDownLatch finishFirst = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		when(scheduler.refreshCategories(any(), anyList(), any())).thenAnswer(invocation -> {
			if (calls.incrementAndGet() == 1) {
				firstLoading.countDown();
				finishFirst.await(10, TimeUnit.SECONDS);
			}
			return 1;
		});
		ensureFreshIn(Context.current().withCancellation(), () -> { });          // request A: loads, holds the chain lock
		assertThat(firstLoading.await(5, TimeUnit.SECONDS)).isTrue();
		Context.CancellableContext requestB = Context.current().withCancellation();
		AtomicInteger followUpB = new AtomicInteger();
		ensureFreshIn(requestB, followUpB::incrementAndGet);                      // request B: queued on the chain lock
		requestB.cancel(new RuntimeException("client gone"));

		finishFirst.countDown();
		Thread.sleep(400);

		verify(scheduler, times(1)).refreshCategories(any(), anyList(), any());   // B never started
		assertThat(followUpB).hasValue(0);
		// B's hourly-cap slot was handed back: the cap of 2 leaves room for one more load
		ensureFreshIn(Context.current().withCancellation(), () -> { });
		verify(scheduler, timeout(2_000).times(2)).refreshCategories(any(), anyList(), any());
	}

	@Test
	void theCancelReachesTheRunningPageLoadThroughTheToken() throws Exception {
		AtomicReference<CancellationToken> seen = new AtomicReference<>();
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		when(scheduler.refreshCategories(any(), anyList(), any())).thenAnswer(invocation -> {
			seen.set(invocation.getArgument(2));
			loading.countDown();
			finish.await(10, TimeUnit.SECONDS);
			return 0;
		});
		Context.CancellableContext request = Context.current().withCancellation();
		ensureFreshIn(request, () -> { });
		assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(seen.get().isCancelled()).isFalse();

		request.cancel(new RuntimeException("client gone"));

		assertThat(seen.get().isCancelled()).isTrue();
		finish.countDown();
	}

	@Test
	void aRequestThatCompletesNormallyDoesNotCancelItsBackgroundRefresh() throws Exception {
		AtomicReference<CancellationToken> seen = new AtomicReference<>();
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		when(scheduler.refreshCategories(any(), anyList(), any())).thenAnswer(invocation -> {
			seen.set(invocation.getArgument(2));
			loading.countDown();
			finish.await(10, TimeUnit.SECONDS);
			return 3;
		});
		AtomicInteger followUps = new AtomicInteger();
		Context.CancellableContext request = Context.current().withCancellation();
		ensureFreshIn(request, followUps::incrementAndGet);
		assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

		request.cancel(null);                       // what gRPC does when the call completes with OK

		assertThat(seen.get().isCancelled()).isFalse();
		finish.countDown();
		long until = System.currentTimeMillis() + 3_000;
		while (followUps.get() == 0 && System.currentTimeMillis() < until) {
			Thread.sleep(20);
		}
		assertThat(followUps).hasValue(1);
	}

	@Test
	void withoutAnyRequestContextBehaviourIsUnchanged() {
		service.ensureFresh(milk, () -> { });

		verify(scheduler, timeout(2_000).times(1)).refreshCategories(eq(ChainId.NEWWORLD), anyList(), any());
	}
}
