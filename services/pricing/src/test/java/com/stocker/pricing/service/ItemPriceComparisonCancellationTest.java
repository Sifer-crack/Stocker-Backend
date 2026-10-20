package com.stocker.pricing.service;

import com.stocker.pricing.service.ItemPriceComparisonService.ItemQuery;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import io.grpc.Context;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemPriceComparisonCancellationTest {

	private final PriceSearchService searchService = mock(PriceSearchService.class);
	private final ItemMatchService matchService = mock(ItemMatchService.class);
	private final ItemPriceComparisonService service = new ItemPriceComparisonService(searchService, matchService,
			mock(ServiceAreaProperties.class), new PricingCacheProperties());
	private final List<ItemQuery> items = List.of(new ItemQuery("1", "milk", ""), new ItemQuery("2", "eggs", ""),
			new ItemQuery("3", "bread", ""));

	@Test
	void stopsBetweenItemsWhenTheCallIsCancelled() {
		Context.CancellableContext context = Context.current().withCancellation();
		when(matchService.matchStored(anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
			context.cancel(new RuntimeException("client went away"));       // cancelled while pricing item 1
			return new ItemMatchService.MatchResult("lexical", List.of(), List.of(), false);
		});

		assertThatThrownBy(() -> context.call(() -> service.compare(items, "")))
				.isInstanceOf(CancellationException.class);

		verify(matchService, times(1)).matchStored(anyString(), anyString(), any(), any(), any());
	}

	@Test
	void pricesEveryItemWhenNotCancelled() {
		when(matchService.matchStored(anyString(), anyString(), any(), any(), any()))
				.thenReturn(new ItemMatchService.MatchResult("lexical", List.of(), List.of(), false));

		assertThat(service.compare(items, "")).hasSize(3);

		verify(matchService, times(3)).matchStored(anyString(), anyString(), any(), any(), any());
	}
}
