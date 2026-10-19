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

/**
 * What the app receives when it compares a shopping list (POST /api/pricing/compare): the totals, the
 * recommendation, the savings per option, and the service-area error message.
 */
@WebFluxTest(PricingController.class)
class CompareAcceptanceTest {

	private static final String OUTSIDE = "You are outside Stocker's service area. Try again from a supported region.";

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private PriceRecordServiceGrpc.PriceRecordServiceBlockingStub pricing;

	private static StoreTotal total(String chain, double amount, double savings) {
		return StoreTotal.newBuilder().setChainId(chain).setTotalAmount(amount).setCurrency("NZD").setItemsPriced(3)
				.setSavingsAmount(savings).build();
	}

	@Test
	void theAppReceivesTotalsRecommendationAndSavingsForEveryOption() {
		when(pricing.compareShoppingList(any(CompareShoppingListRequest.class))).thenReturn(
				CompareShoppingListResponse.newBuilder().setCheapestChainId("paknsave").setEffectiveSelectedChainId("newworld")
						.addStoreTotals(total("paknsave", 17.00, 2.00)).addStoreTotals(total("newworld", 19.00, 0.0)).build());

		webTestClient.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"region\":\"auckland\",\"selectedChainId\":\"newworld\",\"items\":[{\"itemId\":\"milk\",\"quantity\":2}]}")
				.exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.cheapestChainId").isEqualTo("paknsave")
				.jsonPath("$.selectedChainId").isEqualTo("newworld")
				.jsonPath("$.storeTotals[0].chainId").isEqualTo("paknsave")
				.jsonPath("$.storeTotals[0].totalAmount").isEqualTo(17.0)
				.jsonPath("$.storeTotals[0].savingsAmount").isEqualTo(2.0)
				.jsonPath("$.storeTotals[1].savingsAmount").isEqualTo(0.0);
	}

	@Test
	void outsideTheServiceAreaTheAppGetsAnExplanationItCanDisplay() {
		when(pricing.compareShoppingList(any(CompareShoppingListRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription(OUTSIDE).asRuntimeException());

		webTestClient.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"region\":\"queenstown\",\"items\":[{\"itemId\":\"milk\",\"quantity\":1}]}")
				.exchange().expectStatus().isEqualTo(422).expectBody().jsonPath("$.error").isEqualTo(OUTSIDE);
	}

	@Test
	void anEmptyListGetsTheSameExplanation() {
		when(pricing.compareShoppingList(any(CompareShoppingListRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription(OUTSIDE).asRuntimeException());

		webTestClient.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"region\":\"auckland\",\"items\":[]}")
				.exchange().expectStatus().isEqualTo(422).expectBody().jsonPath("$.error").isEqualTo(OUTSIDE);
	}
}
