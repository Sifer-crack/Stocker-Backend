package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.CompareItemPricesRequest;
import com.stocker.pricing.api.grpc.v1.CompareItemPricesResponse;
import com.stocker.pricing.api.grpc.v1.ComparisonItem;
import com.stocker.pricing.api.grpc.v1.ItemPriceComparison;
import com.stocker.pricing.service.ItemPriceComparisonService;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.ItemPriceComparisonService.ItemComparison;
import com.stocker.pricing.service.ItemPriceComparisonService.StoreQuote;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceRecordGrpcControllerCompareItemPricesTest {

	private static final OffsetDateTime CAPTURED_AT = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);

	private ItemPriceComparisonService itemPriceComparisonService;
	private PriceRecordGrpcController controller;
	@SuppressWarnings("unchecked")
	private StreamObserver<CompareItemPricesResponse> observer = mock(StreamObserver.class);

	@BeforeEach
	void setUp() {
		itemPriceComparisonService = mock(ItemPriceComparisonService.class);
		controller = new PriceRecordGrpcController(
				mock(PriceFetcherService.class),
				mock(PriceSearchService.class),
				mock(ShoppingListComparisonService.class),
				mock(SavingsCalculatorService.class),
				itemPriceComparisonService,
				mock(ItemMatchService.class));
	}

	@Test
	void mapsCheapestAndTheFullPriceListIntoTheResponse() {
		StoreQuote cheap = new StoreQuote("paknsave", "ps:1", new BigDecimal("4.20"), "NZD", true, CAPTURED_AT);
		StoreQuote dear = new StoreQuote("newworld", "nw:1", new BigDecimal("4.80"), "NZD", false, CAPTURED_AT);
		when(itemPriceComparisonService.compare(any(), eq("auckland")))
				.thenReturn(List.of(new ItemComparison("item-1", List.of(cheap, dear))));

		controller.compareItemPrices(request("auckland"), observer);

		ArgumentCaptor<CompareItemPricesResponse> captor = ArgumentCaptor.forClass(CompareItemPricesResponse.class);
		verify(observer).onNext(captor.capture());
		verify(observer).onCompleted();
		ItemPriceComparison comparison = captor.getValue().getComparisons(0);
		assertEquals("item-1", comparison.getItemId());
		assertTrue(comparison.getFound());
		assertEquals("paknsave", comparison.getCheapest().getChainId());
		assertEquals("ps:1", comparison.getCheapest().getStoreId());
		assertEquals(4.20, comparison.getCheapest().getPriceAmount());
		assertTrue(comparison.getCheapest().getPromoFlag());
		assertEquals(CAPTURED_AT.toEpochSecond(), comparison.getCheapest().getCapturedAt().getSeconds());
		assertEquals(2, comparison.getPricesCount());
		assertEquals("newworld", comparison.getPrices(1).getChainId());
	}

	@Test
	void notFoundLeavesCheapestUnsetButStillCompletesNormally() {
		when(itemPriceComparisonService.compare(any(), eq("")))
				.thenReturn(List.of(new ItemComparison("item-1", List.of())));

		controller.compareItemPrices(request(""), observer);

		ArgumentCaptor<CompareItemPricesResponse> captor = ArgumentCaptor.forClass(CompareItemPricesResponse.class);
		verify(observer).onNext(captor.capture());
		verify(observer).onCompleted();
		ItemPriceComparison comparison = captor.getValue().getComparisons(0);
		assertFalse(comparison.getFound());
		assertFalse(comparison.hasCheapest());
		assertEquals(0, comparison.getPricesCount());
	}

	@Test
	void unsupportedRegionFailsWithInvalidArgument() {
		when(itemPriceComparisonService.compare(any(), eq("mars"))).thenThrow(ServiceAreaException.outsideServiceArea());

		controller.compareItemPrices(request("mars"), observer);

		ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
		verify(observer).onError(captor.capture());
		verify(observer, never()).onNext(any());
		assertEquals(Status.Code.INVALID_ARGUMENT, ((StatusRuntimeException) captor.getValue()).getStatus().getCode());
	}

	private static CompareItemPricesRequest request(String region) {
		return CompareItemPricesRequest.newBuilder()
				.setRegion(region)
				.addItems(ComparisonItem.newBuilder().setItemId("item-1").setSearchTerm("milk 2l").setCategory("milk"))
				.build();
	}
}
