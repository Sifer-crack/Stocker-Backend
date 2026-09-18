package com.stocker.pricing.refresh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.service.PriceSearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PriceRefreshRequestConsumerTest {

	private PriceSearchService priceSearchService;
	private PriceRefreshRequestConsumer consumer;

	@BeforeEach
	void setUp() {
		priceSearchService = mock(PriceSearchService.class);
		consumer = new PriceRefreshRequestConsumer(priceSearchService, new ObjectMapper());
	}

	@Test
	void deserializesAndDelegatesToRefreshFromProvider() throws Exception {
		String payload = new ObjectMapper().writeValueAsString(
				new PriceRefreshRequest("item-1", "rice", List.of("https://www.limchhour.co.nz"), "grocery"));

		consumer.onRefreshRequested(payload);

		verify(priceSearchService).refreshFromProvider(
				eq("rice"), eq("item-1"), eq(List.of("https://www.limchhour.co.nz")), eq("grocery"));
	}

	@Test
	void malformedPayloadIsLoggedAndSwallowedNotThrown() {
		consumer.onRefreshRequested("not json {");

		verify(priceSearchService, never()).refreshFromProvider(
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}
}
