package com.stocker.pricing.service;

import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceSearchServiceTest {

	private WebFetcher webFetcher;
	private PriceRecordRepository repository;
	private PriceSearchService service;

	@BeforeEach
	void setUp() {
		webFetcher = mock(WebFetcher.class);
		repository = mock(PriceRecordRepository.class);
		service = new PriceSearchService(webFetcher, repository);
	}

	@Test
	void fetchesMapsAndSaves() {
		RawProduct product = RawProduct.builder()
				.name("Rice 5kg")
				.price(new BigDecimal("12.90"))
				.currency("NZD")
				.storeUrl("https://www.limchhour.co.nz/rice")
				.chainId("limchhour")
				.build();
		when(webFetcher.fetch(any(WebFetchRequest.class))).thenReturn(List.of(product, product));
		PriceRecord saved = PriceRecord.builder().itemId("item-1").build();
		when(repository.save(any(PriceRecord.class))).thenReturn(saved);

		List<PriceRecord> result = service.search("rice", "item-1", List.of("https://www.limchhour.co.nz"), "grocery");

		assertEquals(2, result.size());
		ArgumentCaptor<WebFetchRequest> requestCaptor = ArgumentCaptor.forClass(WebFetchRequest.class);
		verify(webFetcher).fetch(requestCaptor.capture());
		assertEquals("rice", requestCaptor.getValue().getSearchTerm());
		assertEquals(List.of("https://www.limchhour.co.nz"), requestCaptor.getValue().getStoreUrls());
		assertEquals("grocery", requestCaptor.getValue().getCategory());
		ArgumentCaptor<PriceRecord> recordCaptor = ArgumentCaptor.forClass(PriceRecord.class);
		verify(repository, org.mockito.Mockito.times(2)).save(recordCaptor.capture());
		PriceRecord mapped = recordCaptor.getAllValues().get(0);
		assertEquals("item-1", mapped.getItemId());
		assertEquals("https://www.limchhour.co.nz/rice", mapped.getStoreId());
		assertEquals("limchhour", mapped.getChainId());
		assertEquals(new BigDecimal("12.90"), mapped.getPriceAmount());
	}

	@Test
	void returnsEmptyWhenFetcherThrows() {
		when(webFetcher.fetch(any(WebFetchRequest.class)))
				.thenThrow(new IllegalStateException("provider down"));

		List<PriceRecord> result = service.search("rice", "item-1", List.of(), null);

		assertTrue(result.isEmpty());
		verify(repository, never()).save(any(PriceRecord.class));
	}
}