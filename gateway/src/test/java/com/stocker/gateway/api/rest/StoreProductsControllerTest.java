package com.stocker.gateway.api.rest;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import com.stocker.pricing.api.grpc.v1.ChainProduct;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(StoreProductsController.class)
class StoreProductsControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private ChainCatalogGrpc.ChainCatalogBlockingStub chainCatalogBlockingStub;

	@Test
	void mapsAStoresProductsToJson() {
		when(chainCatalogBlockingStub.listProducts(any(ListChainProductsRequest.class))).thenReturn(
				ListChainProductsResponse.newBuilder()
						.addProducts(ChainProduct.newBuilder().setItemId("paknsave:1").setName("Anchor Milk 2l")
								.setBrand("Anchor").setPriceAmount(4.80).setCurrency("NZD").setPromoFlag(true)
								.setPackSize("2l").setCapturedAt(Timestamp.newBuilder().setSeconds(1_792_375_200L)))
						.addProducts(ChainProduct.newBuilder().setItemId("paknsave:2").setName("Milk").setPriceAmount(2.0))
						.build());

		webTestClient.get().uri("/api/pricing/stores/paknsave/products?query=milk&limit=20")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.products.length()").isEqualTo(2)
				.jsonPath("$.products[0].itemId").isEqualTo("paknsave:1")
				.jsonPath("$.products[0].name").isEqualTo("Anchor Milk 2l")
				.jsonPath("$.products[0].brand").isEqualTo("Anchor")
				.jsonPath("$.products[0].priceAmount").isEqualTo(4.80)
				.jsonPath("$.products[0].promoFlag").isEqualTo(true)
				.jsonPath("$.products[0].packSize").isEqualTo("2l")
				.jsonPath("$.products[0].capturedAt").isEqualTo("2026-10-19T02:00:00Z")
				.jsonPath("$.products[1].brand").doesNotExist()
				.jsonPath("$.products[1].packSize").doesNotExist()
				.jsonPath("$.products[1].capturedAt").doesNotExist();
	}

	@Test
	void passesTheChainQueryAndLimitToPricing() {
		when(chainCatalogBlockingStub.listProducts(any(ListChainProductsRequest.class)))
				.thenReturn(ListChainProductsResponse.getDefaultInstance());

		webTestClient.get().uri("/api/pricing/stores/newworld/products?query=eggs&limit=7").exchange().expectStatus().isOk();

		ArgumentCaptor<ListChainProductsRequest> captor = ArgumentCaptor.forClass(ListChainProductsRequest.class);
		verify(chainCatalogBlockingStub).listProducts(captor.capture());
		assertEquals("newworld", captor.getValue().getChainId());
		assertEquals("eggs", captor.getValue().getQuery());
		assertEquals(7, captor.getValue().getLimit());
	}

	@Test
	void queryAndLimitAreOptional() {
		when(chainCatalogBlockingStub.listProducts(any(ListChainProductsRequest.class)))
				.thenReturn(ListChainProductsResponse.getDefaultInstance());

		webTestClient.get().uri("/api/pricing/stores/woolworths/products").exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.products.length()").isEqualTo(0);

		ArgumentCaptor<ListChainProductsRequest> captor = ArgumentCaptor.forClass(ListChainProductsRequest.class);
		verify(chainCatalogBlockingStub).listProducts(captor.capture());
		assertEquals("", captor.getValue().getQuery());
		assertEquals(0, captor.getValue().getLimit());
	}

	@Test
	void anUnknownStoreBecomes422WithTheMessage() {
		when(chainCatalogBlockingStub.listProducts(any(ListChainProductsRequest.class))).thenThrow(
				Status.INVALID_ARGUMENT.withDescription("Unknown store chain 'countdown'").asRuntimeException());

		webTestClient.get().uri("/api/pricing/stores/countdown/products").exchange()
				.expectStatus().isEqualTo(422)
				.expectBody().jsonPath("$.error").isEqualTo("Unknown store chain 'countdown'");
	}
}
