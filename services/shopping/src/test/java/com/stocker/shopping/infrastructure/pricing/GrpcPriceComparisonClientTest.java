package com.stocker.shopping.infrastructure.pricing;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.CompareItemPricesRequest;
import com.stocker.pricing.api.grpc.v1.CompareItemPricesResponse;
import com.stocker.pricing.api.grpc.v1.ItemPriceComparison;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.StorePrice;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.port.PricingRejectedException;
import com.stocker.shopping.application.port.PricingUnavailableException;
import io.grpc.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcPriceComparisonClientTest {

	private PriceRecordServiceGrpc.PriceRecordServiceBlockingStub stub;
	private GrpcPriceComparisonClient client;

	@BeforeEach
	void setUp() {
		stub = mock(PriceRecordServiceGrpc.PriceRecordServiceBlockingStub.class);
		when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
		client = new GrpcPriceComparisonClient(stub, Duration.ofSeconds(8));
	}

	@Test
	void sendsTheItemUnderADeadlineAndMapsEveryPriceBack() {
		when(stub.compareItemPrices(any())).thenReturn(CompareItemPricesResponse.newBuilder()
				.addComparisons(ItemPriceComparison.newBuilder()
						.setItemId("item-1").setFound(true)
						.addPrices(StorePrice.newBuilder().setChainId("paknsave").setStoreId("ps:1")
								.setPriceAmount(4.2).setCurrency("NZD").setPromoFlag(true)
								.setCapturedAt(Timestamp.newBuilder().setSeconds(1758456000L)))
						.addPrices(StorePrice.newBuilder().setChainId("newworld").setStoreId("nw:1")
								.setPriceAmount(4.8).setCurrency("NZD")))
				.build());

		List<PricePoint> prices = client.compare("item-1", "full cream milk 2L", "milk", "auckland");

		verify(stub).withDeadlineAfter(8000L, TimeUnit.MILLISECONDS);
		ArgumentCaptor<CompareItemPricesRequest> request = ArgumentCaptor.forClass(CompareItemPricesRequest.class);
		verify(stub).compareItemPrices(request.capture());
		assertEquals("auckland", request.getValue().getRegion());
		assertEquals("item-1", request.getValue().getItems(0).getItemId());
		assertEquals("full cream milk 2L", request.getValue().getItems(0).getSearchTerm());
		assertEquals("milk", request.getValue().getItems(0).getCategory());

		assertEquals(2, prices.size());
		assertEquals("paknsave", prices.get(0).chainId());
		assertEquals(new BigDecimal("4.20"), prices.get(0).priceAmount());
		assertTrue(prices.get(0).promoFlag());
		assertEquals(1758456000L, prices.get(0).capturedAt().toEpochSecond());
		assertNull(prices.get(1).capturedAt());
	}

	@Test
	void notFoundIsAnEmptyListNotAnError() {
		when(stub.compareItemPrices(any())).thenReturn(CompareItemPricesResponse.newBuilder()
				.addComparisons(ItemPriceComparison.newBuilder().setItemId("item-1").setFound(false)).build());

		assertTrue(client.compare("item-1", "milk", null, null).isEmpty());
	}

	@Test
	void invalidArgumentMeansPricingRejectedTheRequest() {
		when(stub.compareItemPrices(any()))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("outside service area").asRuntimeException());

		PricingRejectedException ex = assertThrows(PricingRejectedException.class,
				() -> client.compare("item-1", "milk", null, "mars"));
		assertEquals("outside service area", ex.getMessage());
	}

	@Test
	void timeoutsAndFailuresAreUnavailableAndAreNeverRetried() {
		when(stub.compareItemPrices(any())).thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
		assertThrows(PricingUnavailableException.class, () -> client.compare("item-1", "milk", null, null));

		// doThrow: re-stubbing via when(stub.call()) would invoke the mock, which already throws.
		doThrow(Status.UNAVAILABLE.asRuntimeException()).when(stub).compareItemPrices(any());
		assertThrows(PricingUnavailableException.class, () -> client.compare("item-1", "milk", null, null));

		verify(stub, times(2)).compareItemPrices(any());
	}
}
