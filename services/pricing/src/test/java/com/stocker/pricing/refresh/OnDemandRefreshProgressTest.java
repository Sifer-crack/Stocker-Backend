package com.stocker.pricing.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** B8: what the background refresh is doing per chain can be read back (cheaply, without a new request) by asking again. */
class OnDemandRefreshProgressTest {

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
		PricingCacheProperties properties = new PricingCacheProperties();
		properties.getSnapshot().setTtl(Duration.ZERO);
		service = new OnDemandRefreshService(new NormalizedProductSnapshot(repository, properties), CategoryMap.loadDefault(),
				CategoryRouter.loadDefault(), properties, provider);
		OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
		when(repository.newestByChain("milk")).thenReturn(List.of(new Object[] { "newworld", old },
				new Object[] { "paknsave", old }, new Object[] { "woolworths", old }));
	}

	@Test
	void nothingIsReportedForAProductNeverRefreshed() {
		assertThat(service.progressForCommon(milk)).isEmpty();
		assertThat(service.progressForTerm("quinoa")).isEmpty();
	}

	@Test
	void pendingWhileThePageLoadsThenDoneAndAskingAgainStartsNothingNew() throws Exception {
		CountDownLatch started = new CountDownLatch(3);
		CountDownLatch finish = new CountDownLatch(1);
		when(scheduler.refreshCategories(any(), anyList(), any())).thenAnswer(invocation -> {
			started.countDown();
			finish.await(10, TimeUnit.SECONDS);
			return 5;
		});

		service.ensureFresh(milk, () -> { });
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(service.progressForCommon(milk)).containsOnly(Map.entry(ChainId.NEWWORLD, RefreshState.PENDING),
				Map.entry(ChainId.PAKNSAVE, RefreshState.PENDING), Map.entry(ChainId.WOOLWORTHS, RefreshState.PENDING));

		service.ensureFresh(milk, () -> { });   // the shopper asks again: cooldown, no new page load
		assertThat(service.progressForCommon(milk)).containsEntry(ChainId.NEWWORLD, RefreshState.PENDING);

		finish.countDown();
		awaitState(RefreshState.DONE);
		verify(scheduler, times(3)).refreshCategories(any(), anyList(), any());
		service.ensureFresh(milk, () -> { });
		assertThat(service.progressForCommon(milk).values()).containsOnly(RefreshState.DONE);
		verify(scheduler, times(3)).refreshCategories(any(), anyList(), any());
	}

	@Test
	void aPageThatThrowsIsFailedAndTheOthersAreDone() throws Exception {
		when(scheduler.refreshCategories(any(), anyList(), any())).thenAnswer(invocation -> {
			if (invocation.getArgument(0) == ChainId.PAKNSAVE) {
				throw new IllegalStateException("blocked");
			}
			return 3;
		});

		service.ensureFresh(milk, () -> { });

		long deadline = System.currentTimeMillis() + 5_000;
		while (service.progressForCommon(milk).containsValue(RefreshState.PENDING) && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertThat(service.progressForCommon(milk)).containsOnly(Map.entry(ChainId.NEWWORLD, RefreshState.DONE),
				Map.entry(ChainId.PAKNSAVE, RefreshState.FAILED), Map.entry(ChainId.WOOLWORTHS, RefreshState.DONE));
	}

	@Test
	void aChainThatIsNotAllowedAnotherLoadIsSkipped() {
		PricingCacheProperties.OnDemand config = new PricingCacheProperties().getOnDemand();
		config.setEnabled(false);
		PricingCacheProperties properties = new PricingCacheProperties();
		properties.getOnDemand().setEnabled(false);
		properties.getSnapshot().setTtl(Duration.ZERO);
		@SuppressWarnings("unchecked")
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(scheduler);
		OnDemandRefreshService disabled = new OnDemandRefreshService(new NormalizedProductSnapshot(repository, properties),
				CategoryMap.loadDefault(), CategoryRouter.loadDefault(), properties, provider);

		disabled.ensureFresh(milk, () -> { });

		assertThat(disabled.progressForCommon(milk).values()).containsOnly(RefreshState.SKIPPED);
	}

	private void awaitState(RefreshState state) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (!service.progressForCommon(milk).values().stream().allMatch(state::equals)
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
	}
}
