package com.stocker.gateway.api.rest;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainMatch;
import com.stocker.pricing.api.grpc.v1.MatchItemRequest;
import com.stocker.pricing.api.grpc.v1.MatchItemResponse;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(PricingController.class)
class PricingControllerMatchTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceRecordServiceBlockingStub;

	@Test
	void mapsMatchesCheapestFirstAndLabelsAlternatives() {
		when(priceRecordServiceBlockingStub.matchItem(any(MatchItemRequest.class))).thenReturn(
				MatchItemResponse.newBuilder().setMatchMethod("lexical")
						.addChainMatches(match("NEWWORLD", "Anchor Milk 2L", "Anchor", 4.80, 0.95))
						.addChainMatches(match("PAKNSAVE", "Pams Milk 2L", "", 3.90, 0.9))
						.addAlternatives(match("PAKNSAVE", "Pams Milk 1L", "", 2.20, 0.4))
						.build());

		webTestClient.get().uri("/api/pricing/match?term=milk%202L&itemId=abc&category=milk")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.matchMethod").isEqualTo("lexical")
				.jsonPath("$.matches[0].chainId").isEqualTo("PAKNSAVE")
				.jsonPath("$.matches[0].priceAmount").isEqualTo(3.90)
				.jsonPath("$.matches[0].brand").doesNotExist()
				.jsonPath("$.matches[0].matchType").isEqualTo("exact")
				.jsonPath("$.matches[1].brand").isEqualTo("Anchor")
				.jsonPath("$.matches[1].capturedAt").isEqualTo("2026-09-21T12:00:00Z")
				.jsonPath("$.matches[1].score").isEqualTo(0.95)
				.jsonPath("$.alternatives[0].productName").isEqualTo("Pams Milk 1L")
				.jsonPath("$.alternatives[0].matchType").isEqualTo("alternative");
	}

	@Test
	void nothingFoundYetIsAnEmptyOkResponseNotAnError() {
		when(priceRecordServiceBlockingStub.matchItem(any(MatchItemRequest.class)))
				.thenReturn(MatchItemResponse.newBuilder().setMatchMethod("lexical").build());

		webTestClient.get().uri("/api/pricing/match?term=milk&itemId=abc")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.matches.length()").isEqualTo(0)
				.jsonPath("$.alternatives.length()").isEqualTo(0);
	}

	@Test
	void invalidArgumentBecomes422WithTheMessage() {
		when(priceRecordServiceBlockingStub.matchItem(any(MatchItemRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("term and itemId are required").asRuntimeException());

		webTestClient.get().uri("/api/pricing/match?term=%20&itemId=abc")
				.exchange()
				.expectStatus().isEqualTo(422)
				.expectBody().jsonPath("$.error").isEqualTo("term and itemId are required");
	}

	private static ChainMatch match(String chain, String name, String brand, double price, double score) {
		return ChainMatch.newBuilder().setChainId(chain).setStoreId(chain.toLowerCase() + ":").setProductName(name)
				.setBrand(brand).setPriceAmount(price).setCurrency("NZD").setScore(score)
				.setCapturedAt(Timestamp.newBuilder().setSeconds(1789992000L)).build();
	}
}
