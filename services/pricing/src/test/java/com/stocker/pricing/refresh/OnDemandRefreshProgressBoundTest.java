package com.stocker.pricing.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Free-text terms come from users: the per-term progress map must not grow without bound. */
class OnDemandRefreshProgressBoundTest {

	@SuppressWarnings("unchecked")
	@Test
	void theProgressMapStaysWithinItsBoundForAStreamOfDistinctTerms() {
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);          // no scheduler: every routed page is marked SKIPPED
		PricingCacheProperties properties = new PricingCacheProperties();
		OnDemandRefreshService service = new OnDemandRefreshService(
				new NormalizedProductSnapshot(mock(NormalizedProductRepository.class), properties), CategoryMap.loadDefault(),
				CategoryRouter.loadDefault(), properties, provider);

		for (int i = 0; i < 3 * OnDemandRefreshService.MAX_PROGRESS_ENTRIES; i++) {
			service.ensureForTerm("milk variant " + i, null, List.of(ChainId.NEWWORLD), () -> { });
		}

		assertThat(service.progressEntries()).isLessThanOrEqualTo(OnDemandRefreshService.MAX_PROGRESS_ENTRIES + 1);
		assertThat(service.progressEntries()).isGreaterThan(OnDemandRefreshService.MAX_PROGRESS_ENTRIES / 2);
		// the newest term is still readable
		assertThat(service.progressForTerm("milk variant " + (3 * OnDemandRefreshService.MAX_PROGRESS_ENTRIES - 1))).isNotEmpty();
	}
}
