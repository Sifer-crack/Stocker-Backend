package com.stocker.gateway.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import com.stocker.pricing.api.grpc.v1.Suggestion;
import com.stocker.pricing.api.grpc.v1.SuggestProductsRequest;
import com.stocker.pricing.api.grpc.v1.SuggestProductsResponse;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(ProductSearchController.class)
class ProductSuggestControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private ProductSearchGrpc.ProductSearchBlockingStub stub;

	private static SuggestProductsResponse response() {
		return SuggestProductsResponse.newBuilder()
				.addSuggestions(Suggestion.newBuilder().setText("Eggs").setCanonicalKey("eggs").setChainCount(3)
						.addChains("newworld").addChains("paknsave").addChains("woolworths").setSampleSize("12 pack").setProductCount(4))
				.addSuggestions(Suggestion.newBuilder().setText("Egg Fried Rice").setChainCount(1).addChains("newworld")
						.setProductCount(1))
				.build();
	}

	@Test
	void mapsTheSuggestionContract() {
		when(stub.suggestProducts(any(SuggestProductsRequest.class))).thenReturn(response());

		webTestClient.get().uri("/api/pricing/suggest?q={q}&limit=5", "eg")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.q").isEqualTo("eg")
				.jsonPath("$.suggestions.length()").isEqualTo(2)
				.jsonPath("$.suggestions[0].text").isEqualTo("Eggs")
				.jsonPath("$.suggestions[0].canonicalKey").isEqualTo("eggs")
				.jsonPath("$.suggestions[0].chainCount").isEqualTo(3)
				.jsonPath("$.suggestions[0].chains[0]").isEqualTo("newworld")
				.jsonPath("$.suggestions[0].chains[2]").isEqualTo("woolworths")
				.jsonPath("$.suggestions[0].sampleSize").isEqualTo("12 pack")
				.jsonPath("$.suggestions[0].productCount").isEqualTo(4)
				.jsonPath("$.suggestions[1].canonicalKey").value(org.hamcrest.Matchers.nullValue())
				.jsonPath("$.suggestions[1].sampleSize").value(org.hamcrest.Matchers.nullValue());
		verify(stub).suggestProducts(argThat((SuggestProductsRequest r) -> r.getQ().equals("eg") && r.getLimit() == 5));
	}

	@Test
	void aMissingQueryAndLimitReachPricingAsEmptyAndZero() {
		when(stub.suggestProducts(any(SuggestProductsRequest.class))).thenReturn(SuggestProductsResponse.getDefaultInstance());

		webTestClient.get().uri("/api/pricing/suggest")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.q").isEqualTo("")
				.jsonPath("$.suggestions.length()").isEqualTo(0);
		verify(stub).suggestProducts(argThat((SuggestProductsRequest r) -> r.getQ().isEmpty() && r.getLimit() == 0));
	}

	@Test
	void invalidArgumentBecomes422WithTheMessage() {
		when(stub.suggestProducts(any(SuggestProductsRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("bad q").asRuntimeException());

		webTestClient.get().uri("/api/pricing/suggest?q=x")
				.exchange()
				.expectStatus().isEqualTo(422)
				.expectBody().jsonPath("$.error").isEqualTo("bad q");
	}
}
