package com.stocker.pricing.service;

import com.stocker.pricing.service.ItemPriceComparisonService.ItemComparison;
import com.stocker.pricing.service.ItemPriceComparisonService.ItemQuery;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.match.ItemMatchService.MatchResult;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Comparing a shopping list is a read of what has already been stored: it never starts a live refresh, never waits
 * on a supermarket, and (by default) never calls a third-party price provider.
 */
class ItemPriceComparisonStoredOnlyTest {

	private PriceSearchService search;
	private ItemMatchService match;
	private PricingCacheProperties properties;
	private ItemPriceComparisonService service;

	@BeforeEach
	void setUp() {
		search = mock(PriceSearchService.class);
		match = mock(ItemMatchService.class);
		properties = new PricingCacheProperties();
		ServiceAreaProperties area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland"));
		service = new ItemPriceComparisonService(search, match, area, properties);
		when(match.matchStored(any(), any(), any(), any(), any()))
				.thenReturn(new MatchResult(ItemMatchService.METHOD_LEXICAL, List.of(), List.of(), false));
	}

	@Test
	void readsStoredDataOnlyAndKeepsTheCategoryHint() {
		service.compare(List.of(new ItemQuery("item-1", "milk 2l", "milk")), "auckland");

		verify(match).matchStored(eq("milk 2l"), eq("item-1"), isNull(), isNull(), eq("milk"));
		verify(match, never()).match(any(), any());
		verify(match, never()).match(any(), any(), any(), any());
		verify(match, never()).match(any(), any(), any(), any(), any());
	}

	@Test
	void nothingStoredIsANotFoundYetResultWithoutCallingAProvider() {
		ItemComparison result = service.compare(List.of(new ItemQuery("item-1", "milk 2l", "milk")), "auckland").get(0);

		assertThat(result.found()).isFalse();
		verifyNoInteractions(search);
	}

	@Test
	void theProviderFallbackStaysAvailableWhenExplicitlyEnabled() {
		properties.getMatch().setProviderFallback(true);

		service.compare(List.of(new ItemQuery("item-1", "milk 2l", "milk")), "auckland");

		verify(search).search("milk 2l", "item-1", List.of(), "milk");
	}

	@Test
	void theProviderFallbackIsOffByDefault() {
		assertThat(new PricingCacheProperties().getMatch().isProviderFallback()).isFalse();
	}
}
