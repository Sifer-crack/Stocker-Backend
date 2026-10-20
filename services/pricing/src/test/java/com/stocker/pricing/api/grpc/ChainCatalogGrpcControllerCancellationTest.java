package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import com.stocker.pricing.service.ChainProductService;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A cancelled store-products call (client gone) does no further work and never writes to the closed call. */
class ChainCatalogGrpcControllerCancellationTest {

	private ChainProductService service;
	private ChainCatalogGrpcController controller;

	@BeforeEach
	void setUp() {
		service = mock(ChainProductService.class);
		controller = new ChainCatalogGrpcController(service);
	}

	private static Context.CancellableContext cancelledContext() {
		Context.CancellableContext context = Context.current().withCancellation();
		context.cancel(new RuntimeException("client went away"));
		return context;
	}

	@SuppressWarnings("unchecked")
	private static StreamObserver<ListChainProductsResponse> observer() {
		return mock(StreamObserver.class);
	}

	private static ListChainProductsRequest request() {
		return ListChainProductsRequest.newBuilder().setChainId("paknsave").setQuery("milk").build();
	}

	@Test
	void aCancelledCallDoesNoWorkAndWritesNothing() {
		StreamObserver<ListChainProductsResponse> observer = observer();

		cancelledContext().run(() -> controller.listProducts(request(), observer));

		verifyNoInteractions(service);
		verifyNoInteractions(observer);
	}

	@Test
	void aCallCancelledWhileTheListIsBeingBuiltDropsTheReply() {
		StreamObserver<ListChainProductsResponse> observer = observer();
		Context.CancellableContext context = Context.current().withCancellation();
		when(service.list(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
			context.cancel(new RuntimeException("client went away mid-way"));
			return List.of();
		});

		context.run(() -> controller.listProducts(request(), observer));

		verify(service).list("paknsave", "milk", 0);
		verifyNoInteractions(observer);
	}

	@Test
	void aLiveCallStillAnswersNormally() {
		StreamObserver<ListChainProductsResponse> observer = observer();
		when(service.list(anyString(), anyString(), anyInt())).thenReturn(List.of());

		controller.listProducts(request(), observer);

		verify(observer).onNext(any(ListChainProductsResponse.class));
		verify(observer).onCompleted();
	}
}
