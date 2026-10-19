package com.stocker.pricing.refresh;

import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A price lookup must never wait for a supermarket page load. Old prices are answered immediately, flagged stale,
 * and the refresh finishes in the background (then re-runs the match so fresh prices are stored and announced).
 */
class OnDemandRefreshNonBlockingTest {

	private NormalizedProductRepository repository;
	private IngestScheduler scheduler;
	private OnDemandRefreshService service;
	private final CommonProduct milk = CommonProductCatalog.loadDefault().byKey("milk").orElseThrow();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		scheduler = mock(IngestScheduler.class);
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(scheduler);
		service = new OnDemandRefreshService(passThrough(repository, new PricingCacheProperties()), CategoryMap.loadDefault(), CategoryRouter.loadDefault(),
				new PricingCacheProperties(), provider);
	}

	@Test
	void theDefaultIsToNeverWait() {
		assertThat(new PricingCacheProperties().getOnDemand().getDeadline()).isZero();
	}

	@Test
	void aSlowPageLoadDoesNotDelayTheAnswerAndTheFollowUpRunsWhenItFinishes() throws Exception {
		OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
		when(repository.newestByChain("milk")).thenReturn(List.of(
				new Object[] { "newworld", old }, new Object[] { "paknsave", old }, new Object[] { "woolworths", old }));
		CountDownLatch pageLoadStarted = new CountDownLatch(1);
		CountDownLatch finishPageLoad = new CountDownLatch(1);
		when(scheduler.refreshCategories(any(), anyList())).thenAnswer(invocation -> {
			pageLoadStarted.countDown();
			finishPageLoad.await(10, TimeUnit.SECONDS);   // a slow supermarket page
			return 12;
		});
		CountDownLatch followUp = new CountDownLatch(1);

		long start = System.nanoTime();
		OnDemandRefreshService.Outcome outcome = service.ensureFresh(milk, followUp::countDown);
		long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

		assertThat(elapsedMillis).isLessThan(1_000);       // answered without waiting for the page
		assertThat(outcome.stale()).isTrue();              // old prices, flagged
		assertThat(outcome.refreshed()).isFalse();
		assertThat(pageLoadStarted.await(5, TimeUnit.SECONDS)).isTrue();   // but the refresh did start
		assertThat(followUp.getCount()).isEqualTo(1);      // follow-up has not run yet
		finishPageLoad.countDown();
		assertThat(followUp.await(5, TimeUnit.SECONDS)).isTrue();          // and runs once the pages are loaded
	}

	/** Zero ttl: the snapshot passes straight through to the stubbed repository. */
	private static com.stocker.pricing.service.normalize.NormalizedProductSnapshot passThrough(
			com.stocker.pricing.repository.NormalizedProductRepository repository, PricingCacheProperties properties) {
		properties.getSnapshot().setTtl(java.time.Duration.ZERO);
		return new com.stocker.pricing.service.normalize.NormalizedProductSnapshot(repository, properties);
	}
}
