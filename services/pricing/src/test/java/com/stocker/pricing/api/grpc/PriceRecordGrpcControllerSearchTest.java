package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceRecordGrpcControllerSearchTest {

	private PriceFetcherService priceFetcherService;
	private PriceSearchService priceSearchService;
	private PriceRecordGrpcController controller;

	@BeforeEach
	void setUp() {
		priceFetcherService = mock(PriceFetcherService.class);
		priceSearchService = mock(PriceSearchService.class);
		controller = new PriceRecordGrpcController(priceFetcherService, priceSearchService);
	}

	@Test
	void searchFetchesWebResultsAndMapsThem() {
		com.stocker.pricing.model.PriceRecord entity = com.stocker.pricing.model.PriceRecord.builder()
				.id(UUID.randomUUID())
				.itemId("item-1")
				.storeId("https://www.paknsave.co.nz/product/9")
				.chainId("paknsave")
				.channel("pickup")
				.priceAmount(new BigDecimal("3.90"))
				.currency("NZD")
				.capturedAt(OffsetDateTime.now())
				.createdAt(OffsetDateTime.now())
				.build();
		when(priceSearchService.search(any(), any(), any(), any())).thenReturn(List.of(entity));
		@SuppressWarnings("unchecked")
		StreamObserver<SearchResponse> observer = mock(StreamObserver.class);

		controller.search(SearchRequest.newBuilder()
				.setSearchTerm("milk")
				.setItemId("item-1")
				.build(), observer);

		ArgumentCaptor<SearchResponse> captor = ArgumentCaptor.forClass(SearchResponse.class);
		verify(observer).onNext(captor.capture());
		verify(observer).onCompleted();
		assertEquals(1, captor.getValue().getPriceRecordsCount());
		assertEquals("item-1", captor.getValue().getPriceRecords(0).getItemId());
		assertEquals("paknsave", captor.getValue().getPriceRecords(0).getChainId());
		assertEquals(3.9, captor.getValue().getPriceRecords(0).getPriceAmount());
	}

	@Test
	void searchWithoutItemIdReturnsEmpty() {
		@SuppressWarnings("unchecked")
		StreamObserver<SearchResponse> observer = mock(StreamObserver.class);

		controller.search(SearchRequest.newBuilder().setSearchTerm("milk").build(), observer);

		verify(observer).onNext(SearchResponse.getDefaultInstance());
		verify(observer).onCompleted();
		verify(priceSearchService, never()).search(any(), any(), any(), any());
	}
}