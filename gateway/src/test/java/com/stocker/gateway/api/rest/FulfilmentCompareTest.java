package com.stocker.gateway.api.rest;

import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.FulfilmentOption;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.StoreTotal;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Acceptance: given data containing known travel / delivery / click-and-collect costs, the app receives each
 * option's total and the true cheapest option including those costs. Fixture: docs/features/fulfilment-costs.md
 * (items totals paknsave 17.00, woolworths 17.70, newworld 19.00).
 */
@WebFluxTest(PricingController.class)
class FulfilmentCompareTest {

	private static final String BODY_ALL = "{\"region\":\"auckland\",\"items\":[{\"itemId\":\"milk\",\"quantity\":2}]}";

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private PriceRecordServiceGrpc.PriceRecordServiceBlockingStub pricing;

	private static FulfilmentOption opt(String method, double cost, double total) {
		return FulfilmentOption.newBuilder().setMethod(method).setCost(cost).setTotalAmount(total).build();
	}

	private static StoreTotal chain(String id, double items, double savings, String method, double cost, double withF,
			FulfilmentOption... options) {
		StoreTotal.Builder b = StoreTotal.newBuilder().setChainId(id).setTotalAmount(items).setCurrency("NZD")
				.setItemsPriced(3).setSavingsAmount(savings).setTotalWithFulfilment(withF).setBestMethod(method)
				.setFulfilmentCost(cost);
		for (FulfilmentOption o : options) {
			b.addFulfilmentOptions(o);
		}
		return b.build();
	}

	private void stub(String cheapest, StoreTotal... totals) {
		CompareShoppingListResponse.Builder r = CompareShoppingListResponse.newBuilder().setCheapestChainId(cheapest)
				.setEffectiveSelectedChainId(cheapest);
		for (StoreTotal t : totals) {
			r.addStoreTotals(t);
		}
		when(pricing.compareShoppingList(any(CompareShoppingListRequest.class))).thenReturn(r.build());
	}

	private WebTestClient.BodyContentSpec post(String body) {
		return webTestClient.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
				.exchange().expectStatus().isOk().expectBody();
	}

	@Test
	void travelCostsDecideTheCheapestOption() {
		stub("woolworths",
				chain("woolworths", 17.70, 0.0, "in_store", 0.50, 18.20, opt("in_store", 0.50, 18.20)),
				chain("newworld", 19.00, 0.0, "in_store", 0.0, 19.00, opt("in_store", 0.0, 19.00)),
				chain("paknsave", 17.00, 0.0, "in_store", 4.00, 21.00, opt("in_store", 4.00, 21.00)));

		post(BODY_ALL)
				.jsonPath("$.cheapestChainId").isEqualTo("woolworths")
				.jsonPath("$.storeTotals[0].chainId").isEqualTo("woolworths")
				.jsonPath("$.storeTotals[0].totalAmount").isEqualTo(17.7)
				.jsonPath("$.storeTotals[0].totalWithFulfilment").isEqualTo(18.2)
				.jsonPath("$.storeTotals[0].bestMethod").isEqualTo("in_store")
				.jsonPath("$.storeTotals[0].fulfilmentCost").isEqualTo(0.5)
				.jsonPath("$.storeTotals[0].fulfilmentOptions[0].method").isEqualTo("in_store")
				.jsonPath("$.storeTotals[0].fulfilmentOptions[0].cost").isEqualTo(0.5)
				.jsonPath("$.storeTotals[0].fulfilmentOptions[0].totalAmount").isEqualTo(18.2)
				.jsonPath("$.storeTotals[2].totalWithFulfilment").isEqualTo(21.0)
				.jsonPath("$.storeTotals[2].fulfilmentCost").isEqualTo(4.0);
	}

	@Test
	void deliveryFeesDecideTheCheapestOption() {
		stub("woolworths",
				chain("woolworths", 17.70, 0.0, "delivery", 6.00, 23.70, opt("delivery", 6.00, 23.70)),
				chain("newworld", 19.00, 0.30, "delivery", 5.00, 24.00, opt("delivery", 5.00, 24.00)),
				chain("paknsave", 17.00, 2.30, "delivery", 9.00, 26.00, opt("delivery", 9.00, 26.00)));

		post("{\"region\":\"auckland\",\"allowedMethods\":[\"delivery\"],\"items\":[{\"itemId\":\"milk\",\"quantity\":2}]}")
				.jsonPath("$.cheapestChainId").isEqualTo("woolworths")
				.jsonPath("$.storeTotals[0].bestMethod").isEqualTo("delivery")
				.jsonPath("$.storeTotals[0].totalWithFulfilment").isEqualTo(23.7)
				.jsonPath("$.storeTotals[1].totalWithFulfilment").isEqualTo(24.0)
				.jsonPath("$.storeTotals[1].fulfilmentOptions[0].method").isEqualTo("delivery")
				.jsonPath("$.storeTotals[2].totalWithFulfilment").isEqualTo(26.0)
				.jsonPath("$.storeTotals[2].fulfilmentOptions[0].cost").isEqualTo(9.0)
				.jsonPath("$.storeTotals[2].fulfilmentOptions[0].totalAmount").isEqualTo(26.0);
	}

	@Test
	void clickAndCollectFeesDecideTheCheapestOption() {
		stub("newworld",
				chain("newworld", 19.00, 0.0, "click_and_collect", 0.0, 19.00, opt("click_and_collect", 0.0, 19.00)),
				chain("paknsave", 17.00, 0.0, "click_and_collect", 3.00, 20.00, opt("click_and_collect", 3.00, 20.00)),
				chain("woolworths", 17.70, 0.0, "click_and_collect", 4.50, 22.20, opt("click_and_collect", 4.50, 22.20)));

		post("{\"region\":\"auckland\",\"allowedMethods\":[\"click_and_collect\"],\"items\":[{\"itemId\":\"milk\",\"quantity\":2}]}")
				.jsonPath("$.cheapestChainId").isEqualTo("newworld")
				.jsonPath("$.storeTotals[0].bestMethod").isEqualTo("click_and_collect")
				.jsonPath("$.storeTotals[0].totalWithFulfilment").isEqualTo(19.0)
				.jsonPath("$.storeTotals[1].totalWithFulfilment").isEqualTo(20.0)
				.jsonPath("$.storeTotals[2].totalWithFulfilment").isEqualTo(22.2)
				.jsonPath("$.storeTotals[2].fulfilmentCost").isEqualTo(4.5);
	}

	@Test
	void mixedMethodsExposeEveryOptionAndTheBestOne() {
		stub("woolworths",
				chain("woolworths", 17.70, 0.0, "in_store", 0.50, 18.20,
						opt("in_store", 0.50, 18.20), opt("click_and_collect", 4.50, 22.20), opt("delivery", 6.00, 23.70)));

		post(BODY_ALL)
				.jsonPath("$.storeTotals[0].bestMethod").isEqualTo("in_store")
				.jsonPath("$.storeTotals[0].fulfilmentOptions.length()").isEqualTo(3)
				.jsonPath("$.storeTotals[0].fulfilmentOptions[1].method").isEqualTo("click_and_collect")
				.jsonPath("$.storeTotals[0].fulfilmentOptions[2].method").isEqualTo("delivery")
				.jsonPath("$.storeTotals[0].fulfilmentOptions[2].totalAmount").isEqualTo(23.7);
	}

	@Test
	void allowedMethodsAreForwardedToPricing() {
		stub("woolworths", chain("woolworths", 17.70, 0.0, "delivery", 6.0, 23.70, opt("delivery", 6.0, 23.70)));

		post("{\"region\":\"auckland\",\"allowedMethods\":[\"delivery\",\"click_and_collect\"],\"items\":[]}");

		ArgumentCaptor<CompareShoppingListRequest> captor = ArgumentCaptor.forClass(CompareShoppingListRequest.class);
		verify(pricing).compareShoppingList(captor.capture());
		assertThat(captor.getValue().getAllowedMethodsList()).containsExactly("delivery", "click_and_collect");
	}

	@Test
	void absentAllowedMethodsForwardAnEmptyList() {
		stub("woolworths", chain("woolworths", 17.70, 0.0, "in_store", 0.0, 17.70, opt("in_store", 0.0, 17.70)));

		post(BODY_ALL);

		ArgumentCaptor<CompareShoppingListRequest> captor = ArgumentCaptor.forClass(CompareShoppingListRequest.class);
		verify(pricing).compareShoppingList(captor.capture());
		assertThat(captor.getValue().getAllowedMethodsList()).isEmpty();
	}

	@Test
	void bestMethodIsNullWhenNoAllowedMethodIsAvailable() {
		stub("woolworths",
				chain("woolworths", 17.70, 0.0, "in_store", 0.0, 17.70, opt("in_store", 0.0, 17.70)),
				StoreTotal.newBuilder().setChainId("paknsave").setTotalAmount(17.0).setCurrency("NZD").setItemsPriced(3)
						.setTotalWithFulfilment(17.0).build());

		post(BODY_ALL)
				.jsonPath("$.storeTotals[1].bestMethod").isEmpty()
				.jsonPath("$.storeTotals[1].fulfilmentOptions.length()").isEqualTo(0)
				.jsonPath("$.storeTotals[1].totalWithFulfilment").isEqualTo(17.0);
		// explicit null (not an empty string)
		webTestClient.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY_ALL)
				.exchange().expectBody(String.class).value(s -> assertThat(s).contains("\"bestMethod\":null"));
	}

	@Test
	void existingFieldsAreUnchanged() {
		stub("paknsave", chain("paknsave", 17.00, 2.0, "in_store", 0.0, 17.00, opt("in_store", 0.0, 17.00)));

		post(BODY_ALL)
				.jsonPath("$.cheapestChainId").isEqualTo("paknsave")
				.jsonPath("$.selectedChainId").isEqualTo("paknsave")
				.jsonPath("$.storeTotals[0].chainId").isEqualTo("paknsave")
				.jsonPath("$.storeTotals[0].totalAmount").isEqualTo(17.0)
				.jsonPath("$.storeTotals[0].currency").isEqualTo("NZD")
				.jsonPath("$.storeTotals[0].itemsPriced").isEqualTo(3)
				.jsonPath("$.storeTotals[0].unavailableItemIds.length()").isEqualTo(0)
				.jsonPath("$.storeTotals[0].savingsAmount").isEqualTo(2.0);
	}

	@Test
	void unknownMethodOrServiceAreaErrorIsA422WithTheMessage() {
		String message = "Unknown fulfilment method: teleport";
		when(pricing.compareShoppingList(any(CompareShoppingListRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription(message).asRuntimeException());

		webTestClient.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"region\":\"auckland\",\"allowedMethods\":[\"teleport\"],\"items\":[]}")
				.exchange().expectStatus().isEqualTo(422).expectBody().jsonPath("$.error").isEqualTo(message);
	}
}
