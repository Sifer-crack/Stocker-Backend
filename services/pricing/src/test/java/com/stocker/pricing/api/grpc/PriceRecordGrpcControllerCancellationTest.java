package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.CompareItemPricesRequest;
import com.stocker.pricing.api.grpc.v1.CompareItemPricesResponse;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.ComparisonItem;
import com.stocker.pricing.api.grpc.v1.FetchRequest;
import com.stocker.pricing.api.grpc.v1.FetchResponse;
import com.stocker.pricing.api.grpc.v1.MatchItemRequest;
import com.stocker.pricing.api.grpc.v1.MatchItemResponse;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import com.stocker.pricing.service.ItemPriceComparisonService;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.match.ItemMatchService;
import io.grpc.Context;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** A cancelled gRPC call (client gone / deadline) does no further work and never writes to the closed call. */
class PriceRecordGrpcControllerCancellationTest {

	private PriceFetcherService fetcherService;
	private PriceSearchService searchService;
	private ShoppingListComparisonService shoppingListService;
	private SavingsCalculatorService savingsService;
	private ItemPriceComparisonService itemComparisonService;
	private ItemMatchService matchService;
	private PriceRecordGrpcController controller;

	@BeforeEach
	void setUp() {
		fetcherService = mock(PriceFetcherService.class);
		searchService = mock(PriceSearchService.class);
		shoppingListService = mock(ShoppingListComparisonService.class);
		savingsService = mock(SavingsCalculatorService.class);
		itemComparisonService = mock(ItemPriceComparisonService.class);
		matchService = mock(ItemMatchService.class);
		controller = new PriceRecordGrpcController(fetcherService, searchService, shoppingListService, savingsService,
				itemComparisonService, matchService);
	}

	private static Context.CancellableContext cancelledContext() {
		Context.CancellableContext context = Context.current().withCancellation();
		context.cancel(new RuntimeException("client went away"));
		return context;
	}

	private void assertNoWorkAndNoReply(StreamObserver<?> observer) {
		verifyNoInteractions(fetcherService, searchService, shoppingListService, savingsService, itemComparisonService,
				matchService);
		verifyNoInteractions(observer);
	}

	@SuppressWarnings("unchecked")
	private static <T> StreamObserver<T> plainObserver() {
		return mock(StreamObserver.class);
	}

	@Test
	void matchItemOnACancelledContextDoesNothing() {
		StreamObserver<MatchItemResponse> observer = plainObserver();
		cancelledContext().run(() -> controller.matchItem(
				MatchItemRequest.newBuilder().setSearchTerm("milk").setItemId("i1").build(), observer));
		assertNoWorkAndNoReply(observer);
	}

	@Test
	void searchOnACancelledContextDoesNothing() {
		StreamObserver<SearchResponse> observer = plainObserver();
		cancelledContext().run(() -> controller.search(
				SearchRequest.newBuilder().setSearchTerm("milk").setItemId("i1").build(), observer));
		assertNoWorkAndNoReply(observer);
	}

	@Test
	void compareItemPricesOnACancelledContextDoesNothing() {
		StreamObserver<CompareItemPricesResponse> observer = plainObserver();
		cancelledContext().run(() -> controller.compareItemPrices(CompareItemPricesRequest.newBuilder()
				.addItems(ComparisonItem.newBuilder().setItemId("i1").setSearchTerm("milk")).build(), observer));
		assertNoWorkAndNoReply(observer);
	}

	@Test
	void compareShoppingListOnACancelledContextDoesNothing() {
		StreamObserver<CompareShoppingListResponse> observer = plainObserver();
		cancelledContext().run(() -> controller.compareShoppingList(CompareShoppingListRequest.newBuilder().build(), observer));
		assertNoWorkAndNoReply(observer);
	}

	@Test
	void fetchOnACancelledContextDoesNothing() {
		StreamObserver<FetchResponse> observer = plainObserver();
		cancelledContext().run(() -> controller.fetch(FetchRequest.newBuilder().setItemId("i1").setStoreId("s1").build(), observer));
		assertNoWorkAndNoReply(observer);
	}

	@Test
	@SuppressWarnings("unchecked")
	void aServerCallObserverThatReportsCancelledIsHonouredEvenWithoutAContext() {
		ServerCallStreamObserver<SearchResponse> observer = mock(ServerCallStreamObserver.class);
		when(observer.isCancelled()).thenReturn(true);

		controller.search(SearchRequest.newBuilder().setSearchTerm("milk").setItemId("i1").build(), observer);

		verifyNoInteractions(searchService);
		verify(observer).isCancelled();
		org.mockito.Mockito.verifyNoMoreInteractions(observer);
	}

	@Test
	void aCallCancelledWhileTheServiceWorksSendsNothing() {
		Context.CancellableContext context = Context.current().withCancellation();
		when(searchService.search(anyString(), anyString(), anyList(), anyString())).thenAnswer(invocation -> {
			context.cancel(new RuntimeException("client went away"));      // client leaves during the work
			return List.of();
		});
		StreamObserver<SearchResponse> observer = plainObserver();

		context.run(() -> controller.search(SearchRequest.newBuilder().setSearchTerm("milk").setItemId("i1").build(), observer));

		verify(searchService).search(anyString(), anyString(), anyList(), anyString());
		verifyNoInteractions(observer);
	}

	@Test
	void compareItemPricesAbandonedByTheServiceMidwaySendsNothing() {
		when(itemComparisonService.compare(anyList(), anyString())).thenThrow(new CancellationException("cancelled"));
		StreamObserver<CompareItemPricesResponse> observer = plainObserver();

		controller.compareItemPrices(CompareItemPricesRequest.newBuilder().setRegion("auckland")
				.addItems(ComparisonItem.newBuilder().setItemId("i1").setSearchTerm("milk")).build(), observer);

		verifyNoInteractions(observer);
	}

	@Test
	void aLiveCallStillAnswersAsBefore() {
		when(searchService.search(any(), any(), anyList(), any())).thenReturn(List.of());
		StreamObserver<SearchResponse> observer = plainObserver();

		controller.search(SearchRequest.newBuilder().setSearchTerm("milk").setItemId("i1").build(), observer);

		verify(observer).onNext(any());
		verify(observer).onCompleted();
	}
}
