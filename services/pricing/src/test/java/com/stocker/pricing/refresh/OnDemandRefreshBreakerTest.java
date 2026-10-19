package com.stocker.pricing.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A chain whose circuit breaker is open is reported as skipped in the search `refresh` map and no page is loaded for it. */
class OnDemandRefreshBreakerTest {

	private IngestScheduler scheduler;
	private OnDemandRefreshService service;
	private final CommonProduct milk = CommonProductCatalog.loadDefault().byKey("milk").orElseThrow();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		NormalizedProductRepository repository = mock(NormalizedProductRepository.class);
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
		when(scheduler.refreshCategories(any(), anyList(), any())).thenReturn(5);
	}

	@Test
	void anOpenChainIsSkippedAndItsPageIsNeverLoaded() throws Exception {
		when(scheduler.isChainBlocked(ChainId.NEWWORLD)).thenReturn(true);

		service.ensureFresh(milk, () -> { });
		Thread.sleep(300);

		assertThat(service.progressForCommon(milk)).containsEntry(ChainId.NEWWORLD, RefreshState.SKIPPED);
		verify(scheduler, never()).refreshCategories(eq(ChainId.NEWWORLD), anyList(), any());
		verify(scheduler).refreshCategories(eq(ChainId.PAKNSAVE), anyList(), any());
		verify(scheduler).refreshCategories(eq(ChainId.WOOLWORTHS), anyList(), any());
	}

	@Test
	void anOpenChainIsSkippedForAFreeTextTermToo() throws Exception {
		when(scheduler.isChainBlocked(ChainId.PAKNSAVE)).thenReturn(true);

		service.ensureForTerm("milk", null, List.of(ChainId.PAKNSAVE), () -> { });

		assertThat(service.progressForTerm("milk")).containsEntry(ChainId.PAKNSAVE, RefreshState.SKIPPED);
		verify(scheduler, never()).refreshCategories(any(), anyList(), any());
	}

	@Test
	void aBlockedSkipDoesNotSpendTheCooldownSoTheChainIsTriedAgainAfterTheCoolDown() throws Exception {
		when(scheduler.isChainBlocked(ChainId.NEWWORLD)).thenReturn(true);
		service.ensureFresh(milk, () -> { });
		Thread.sleep(200);

		when(scheduler.isChainBlocked(ChainId.NEWWORLD)).thenReturn(false);
		service.ensureFresh(milk, () -> { });
		Thread.sleep(300);

		verify(scheduler).refreshCategories(eq(ChainId.NEWWORLD), anyList(), any());
	}
}
