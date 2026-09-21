package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.ShoppingListItem;
import com.stocker.pricing.service.ItemPriceComparisonService;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceRecordGrpcControllerCompareTest {

	private PriceFetcherService priceFetcherService;
	private PriceSearchService priceSearchService;
	private ShoppingListComparisonService shoppingListComparisonService;
	private SavingsCalculatorService savingsCalculatorService;
	private PriceRecordGrpcController controller;
	@SuppressWarnings("unchecked")
	private StreamObserver<CompareShoppingListResponse> observer;

	@BeforeEach
	void setUp() {
		priceFetcherService = mock(PriceFetcherService.class);
		priceSearchService = mock(PriceSearchService.class);
		shoppingListComparisonService = mock(ShoppingListComparisonService.class);
		savingsCalculatorService = mock(SavingsCalculatorService.class);
		controller = new PriceRecordGrpcController(
				priceFetcherService, priceSearchService, shoppingListComparisonService, savingsCalculatorService,
				mock(ItemPriceComparisonService.class),
				mock(ItemMatchService.class));
		observer = mock(StreamObserver.class);
	}

	@Test
	void mapsComparisonAndSavingsIntoTheProtoResponse() {
		ChainTotal cheapest = new ChainTotal("paknsave", new BigDecimal("6.00"), "NZD", 1, 1, List.of(), BigDecimal.ZERO);
		ChainTotal other = new ChainTotal("newworld", new BigDecimal("7.00"), "NZD", 1, 1, List.of(), BigDecimal.ZERO);
		when(shoppingListComparisonService.compare(any(), eq("auckland")))
				.thenReturn(new ComparisonResult(List.of(cheapest, other), "paknsave"));
		when(savingsCalculatorService.calculate(any(), eq("paknsave"))).thenReturn(List.of(
				new ChainSavings("paknsave", BigDecimal.ZERO),
				new ChainSavings("newworld", new BigDecimal("-1.00"))));

		CompareShoppingListRequest request = CompareShoppingListRequest.newBuilder()
				.addItems(ShoppingListItem.newBuilder().setItemId("milk").setQuantity(2).build())
				.setRegion("auckland")
				.build();

		controller.compareShoppingList(request, observer);

		ArgumentCaptor<CompareShoppingListResponse> captor = ArgumentCaptor.forClass(CompareShoppingListResponse.class);
		verify(observer).onNext(captor.capture());
		verify(observer).onCompleted();
		CompareShoppingListResponse response = captor.getValue();
		assertEquals("paknsave", response.getCheapestChainId());
		assertEquals("paknsave", response.getEffectiveSelectedChainId(), "blank request selected_chain_id defaults to cheapest");
		assertEquals(2, response.getStoreTotalsCount());
		assertEquals("paknsave", response.getStoreTotals(0).getChainId());
		assertEquals(6.00, response.getStoreTotals(0).getTotalAmount());
		assertEquals(-1.00, response.getStoreTotals(1).getSavingsAmount());
	}

	@Test
	void unsupportedRegionSignalsInvalidArgumentGrpcStatusInsteadOfAResult() {
		when(shoppingListComparisonService.compare(any(), any())).thenThrow(ServiceAreaException.outsideServiceArea());
		CompareShoppingListRequest request = CompareShoppingListRequest.newBuilder()
				.addItems(ShoppingListItem.newBuilder().setItemId("milk").setQuantity(1).build())
				.setRegion("nowhere")
				.build();

		controller.compareShoppingList(request, observer);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(observer).onError(captor.capture());
		StatusRuntimeException error = assertInstanceOf(StatusRuntimeException.class, captor.getValue());
		assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
		assertEquals(ServiceAreaException.OUTSIDE_SERVICE_AREA_MESSAGE, error.getStatus().getDescription());
		verify(observer, never()).onNext(any());
		verify(observer, never()).onCompleted();
		verify(savingsCalculatorService, never()).calculate(any(), any());
	}

	@Test
	void emptyItemsListAlsoSignalsInvalidArgument() {
		when(shoppingListComparisonService.compare(eq(List.of()), any()))
				.thenThrow(ServiceAreaException.outsideServiceArea());
		CompareShoppingListRequest request =
				CompareShoppingListRequest.newBuilder().setRegion("auckland").build();

		controller.compareShoppingList(request, observer);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(observer).onError(captor.capture());
		assertEquals(
				Status.Code.INVALID_ARGUMENT, ((StatusRuntimeException) captor.getValue()).getStatus().getCode());
	}
}
