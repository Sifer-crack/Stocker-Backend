package com.stocker.gateway.api.rest;

import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.StoreTotal;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(PricingController.class)
class PricingControllerCompareTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceRecordServiceBlockingStub;

	@Test
	void returnsTheMappedComparisonOnSuccess() {
		when(priceRecordServiceBlockingStub.compareShoppingList(any())).thenReturn(
				CompareShoppingListResponse.newBuilder()
						.setCheapestChainId("paknsave")
						.setEffectiveSelectedChainId("paknsave")
						.addStoreTotals(StoreTotal.newBuilder()
								.setChainId("paknsave")
								.setTotalAmount(6.00)
								.setCurrency("NZD")
								.setItemsPriced(1)
								.setSavingsAmount(0.0)
								.build())
						.build());

		webTestClient.post().uri("/api/pricing/compare")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"items":[{"itemId":"milk","quantity":2}],"region":"auckland"}
						""")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.cheapestChainId").isEqualTo("paknsave")
				.jsonPath("$.storeTotals[0].chainId").isEqualTo("paknsave")
				.jsonPath("$.storeTotals[0].totalAmount").isEqualTo(6.00);
	}

	@Test
	void mapsAnOutsideServiceAreaGrpcErrorToHttp422() {
		when(priceRecordServiceBlockingStub.compareShoppingList(any(CompareShoppingListRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("outside service area").asRuntimeException());

		webTestClient.post().uri("/api/pricing/compare")
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"items":[{"itemId":"milk","quantity":1}],"region":"nowhere"}
						""")
				.exchange()
				.expectStatus().isEqualTo(422)
				.expectBody()
				.jsonPath("$.error").isEqualTo("outside service area");
	}
}
