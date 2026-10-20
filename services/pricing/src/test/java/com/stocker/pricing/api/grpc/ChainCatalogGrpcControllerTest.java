package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import com.stocker.pricing.service.ChainProductService;
import com.stocker.pricing.service.ChainProductService.ChainProduct;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainCatalogGrpcControllerTest {

	private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);

	private ChainProductService service;
	private ChainCatalogGrpcController controller;
	@SuppressWarnings("unchecked")
	private final StreamObserver<ListChainProductsResponse> observer = mock(StreamObserver.class);

	@BeforeEach
	void setUp() {
		service = mock(ChainProductService.class);
		controller = new ChainCatalogGrpcController(service);
	}

	private static ListChainProductsRequest request(String chain, String query, int limit) {
		return ListChainProductsRequest.newBuilder().setChainId(chain).setQuery(query).setLimit(limit).build();
	}

	@Test
	void mapsProductsIntoTheResponse() {
		when(service.list("paknsave", "milk", 20)).thenReturn(List.of(
				new ChainProduct("paknsave:1", "Anchor Milk 2l", "Anchor", new BigDecimal("4.20"), "NZD", true, "2l", AT),
				new ChainProduct("paknsave:2", "Milk", null, new BigDecimal("2.00"), null, false, null, AT)));

		controller.listProducts(request("paknsave", "milk", 20), observer);

		ArgumentCaptor<ListChainProductsResponse> captor = ArgumentCaptor.forClass(ListChainProductsResponse.class);
		verify(observer).onNext(captor.capture());
		verify(observer).onCompleted();
		ListChainProductsResponse response = captor.getValue();
		assertEquals(2, response.getProductsCount());
		assertEquals("paknsave:1", response.getProducts(0).getItemId());
		assertEquals("Anchor Milk 2l", response.getProducts(0).getName());
		assertEquals("Anchor", response.getProducts(0).getBrand());
		assertEquals(4.20, response.getProducts(0).getPriceAmount());
		assertTrue(response.getProducts(0).getPromoFlag());
		assertEquals("2l", response.getProducts(0).getPackSize());
		assertEquals(AT.toEpochSecond(), response.getProducts(0).getCapturedAt().getSeconds());
		assertEquals("", response.getProducts(1).getBrand());
		assertEquals("", response.getProducts(1).getCurrency());
		assertFalse(response.getProducts(1).getPromoFlag());
	}

	@Test
	void anUnknownChainIsInvalidArgument() {
		when(service.list("countdown", "", 0)).thenThrow(new IllegalArgumentException("Unknown store chain 'countdown'"));

		controller.listProducts(request("countdown", "", 0), observer);

		ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
		verify(observer).onError(error.capture());
		verify(observer, never()).onNext(any());
		StatusRuntimeException status = (StatusRuntimeException) error.getValue();
		assertEquals(Status.Code.INVALID_ARGUMENT, status.getStatus().getCode());
		assertEquals("Unknown store chain 'countdown'", status.getStatus().getDescription());
	}

	@Test
	void anEmptyCatalogIsAnEmptyResponse() {
		when(service.list("woolworths", "", 0)).thenReturn(List.of());

		controller.listProducts(request("woolworths", "", 0), observer);

		ArgumentCaptor<ListChainProductsResponse> captor = ArgumentCaptor.forClass(ListChainProductsResponse.class);
		verify(observer).onNext(captor.capture());
		assertEquals(0, captor.getValue().getProductsCount());
	}
}
