package com.stocker.pricing.service.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.refresh.OnDemandRefreshService;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemPriceStore;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Real ProductSearchService over a faked normalised-product table; every collaborator that writes is a mock. */
final class SearchHarness {

	final List<NormalizedProduct> table = new ArrayList<>();
	final PriceRecordRepository priceRecords = mock(PriceRecordRepository.class);
	final PriceStatsService stats = mock(PriceStatsService.class);
	final EventPublisher events = mock(EventPublisher.class);
	final OnDemandRefreshService refresh = mock(OnDemandRefreshService.class);
	final NormalizedProductService normalizedService = mock(NormalizedProductService.class);
	final PricingCacheProperties properties = new PricingCacheProperties();
	final ProductSearchService service;

	SearchHarness(NormalizedProduct... rows) {
		table.addAll(List.of(rows));
		NormalizedProductRepository repository = mock(NormalizedProductRepository.class);
		when(repository.findByCapturedAtAfter(any())).thenAnswer(inv -> table.stream()
				.filter(row -> !row.getCapturedAt().isBefore(inv.getArgument(0))).toList());
		when(repository.findByCanonicalKeyAndCapturedAtAfter(any(), any())).thenAnswer(inv -> table.stream()
				.filter(row -> inv.getArgument(0).equals(row.getCanonicalKey()))
				.filter(row -> !row.getCapturedAt().isBefore(inv.getArgument(1))).toList());
		when(priceRecords.save(any(PriceRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		when(refresh.ensureFresh(any(), any())).thenReturn(new OnDemandRefreshService.Outcome(false, false));
		when(refresh.ensureForTerm(any(), any(), any(), any())).thenReturn(new OnDemandRefreshService.Outcome(false, false));
		properties.getSnapshot().setTtl(Duration.ZERO);   // pass straight through to the faked table
		NormalizedProductSnapshot snapshot = new NormalizedProductSnapshot(repository, properties);
		service = new ProductSearchService(snapshot, SearchFixtures.CATALOG, refresh,
				new ItemPriceStore(priceRecords, stats, events), normalizedService, properties);
		service.setPersistExecutor(Runnable::run);   // store synchronously so assertions can see it
	}
}
