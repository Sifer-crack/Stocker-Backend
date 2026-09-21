package com.stocker.pricing.api.grpc;

import com.stocker.pricing.api.grpc.v1.PriceRecord;
import com.stocker.pricing.api.grpc.v1.SaveRequest;
import com.stocker.pricing.api.grpc.v1.SaveResponse;
import com.stocker.pricing.service.ItemPriceComparisonService;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.ShoppingListComparisonService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Regression coverage for the save-path bugs fixed in toEntity/save (see TASKS.md). */
class PriceRecordGrpcControllerSaveTest {

	private PriceFetcherService priceFetcherService;
	private PriceSearchService priceSearchService;
	private PriceRecordGrpcController controller;
	@SuppressWarnings("unchecked")
	private StreamObserver<SaveResponse> observer;

	@BeforeEach
	void setUp() {
		priceFetcherService = mock(PriceFetcherService.class);
		priceSearchService = mock(PriceSearchService.class);
		controller = new PriceRecordGrpcController(
				priceFetcherService,
				priceSearchService,
				mock(ShoppingListComparisonService.class),
				mock(SavingsCalculatorService.class),
				mock(ItemPriceComparisonService.class),
				mock(ItemMatchService.class));
		observer = mock(StreamObserver.class);
	}

	@Test
	void rejectsMissingItemIdOrStoreId() {
		SaveRequest request = SaveRequest.newBuilder()
				.setPriceRecord(PriceRecord.newBuilder().setChannel("pickup").build())
				.build();

		controller.save(request, observer);

		assertRejectedWithoutSaving();
	}

	@Test
	void rejectsInvalidChannel() {
		SaveRequest request = SaveRequest.newBuilder()
				.setPriceRecord(validRecordBuilder().setChannel("not_a_real_channel").build())
				.build();

		controller.save(request, observer);

		assertRejectedWithoutSaving();
	}

	@Test
	void rejectsNegativePrice() {
		SaveRequest request = SaveRequest.newBuilder()
				.setPriceRecord(validRecordBuilder().setPriceAmount(-1.0).build())
				.build();

		controller.save(request, observer);

		assertRejectedWithoutSaving();
	}

	@Test
	void parsesRawAttributesJsonIntoMap() {
		when(priceFetcherService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		SaveRequest request = SaveRequest.newBuilder()
				.setPriceRecord(validRecordBuilder().setRawAttributes("{\"brand\":\"Anchor\"}").build())
				.build();

		controller.save(request, observer);

		com.stocker.pricing.model.PriceRecord saved = captureSavedEntity();
		assertEquals("Anchor", saved.getRawAttributes().get("brand"));
	}

	@Test
	void malformedRawAttributesJsonDefaultsToEmptyMapInsteadOfThrowing() {
		when(priceFetcherService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		SaveRequest request = SaveRequest.newBuilder()
				.setPriceRecord(validRecordBuilder().setRawAttributes("not json {").build())
				.build();

		controller.save(request, observer);

		com.stocker.pricing.model.PriceRecord saved = captureSavedEntity();
		assertTrue(saved.getRawAttributes().isEmpty());
	}

	@Test
	void zeroTimestampCapturedAtDefaultsToNowInsteadOfNull() {
		when(priceFetcherService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		// capturedAt left unset on the builder -> zero proto Timestamp.
		SaveRequest request = SaveRequest.newBuilder().setPriceRecord(validRecordBuilder().build()).build();

		controller.save(request, observer);

		com.stocker.pricing.model.PriceRecord saved = captureSavedEntity();
		assertNotNull(saved.getCapturedAt());
	}

	@Test
	void acceptsValidClickAndCollectChannelAndZeroPrice() {
		when(priceFetcherService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		SaveRequest request = SaveRequest.newBuilder()
				.setPriceRecord(validRecordBuilder().setChannel("click_and_collect").setPriceAmount(0.0).build())
				.build();

		controller.save(request, observer);

		ArgumentCaptor<SaveResponse> captor = ArgumentCaptor.forClass(SaveResponse.class);
		verify(observer).onNext(captor.capture());
		assertTrue(captor.getValue().getSaved());
	}

	private void assertRejectedWithoutSaving() {
		ArgumentCaptor<SaveResponse> captor = ArgumentCaptor.forClass(SaveResponse.class);
		verify(observer).onNext(captor.capture());
		assertEquals(false, captor.getValue().getSaved());
		verify(priceFetcherService, never()).save(any());
	}

	private com.stocker.pricing.model.PriceRecord captureSavedEntity() {
		ArgumentCaptor<com.stocker.pricing.model.PriceRecord> captor =
				ArgumentCaptor.forClass(com.stocker.pricing.model.PriceRecord.class);
		verify(priceFetcherService).save(captor.capture());
		return captor.getValue();
	}

	private static PriceRecord.Builder validRecordBuilder() {
		return PriceRecord.newBuilder()
				.setItemId("item-1")
				.setStoreId("store-1")
				.setChannel("pickup")
				.setPriceAmount(3.5)
				.setCurrency("NZD");
	}
}
