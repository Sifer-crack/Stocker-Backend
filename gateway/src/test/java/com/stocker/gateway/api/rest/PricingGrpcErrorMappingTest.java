package com.stocker.gateway.api.rest;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.MatchItemRequest;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import com.stocker.pricing.api.grpc.v1.SearchProductsRequest;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SelectProductRequest;
import com.stocker.pricing.api.grpc.v1.SuggestProductsRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Every pricing-backed endpoint maps the same gRPC statuses the same way, with a JSON body carrying the message. */
@WebFluxTest({PricingController.class, ProductSearchController.class, StoreProductsController.class})
class PricingGrpcErrorMappingTest {

	@Autowired
	private WebTestClient web;

	@MockitoBean
	private PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceStub;
	@MockitoBean
	private ProductSearchGrpc.ProductSearchBlockingStub productStub;
	@MockitoBean
	private ChainCatalogGrpc.ChainCatalogBlockingStub chainStub;

	private record Endpoint(String name, Consumer<StatusRuntimeException> failWith, Supplier<WebTestClient.ResponseSpec> call) {
	}

	private List<Endpoint> endpoints() {
		return List.of(
				new Endpoint("search", e -> when(priceStub.search(any(SearchRequest.class))).thenThrow(e),
						() -> web.get().uri("/api/pricing/search?term=a&itemId=b").exchange()),
				new Endpoint("compare", e -> when(priceStub.compareShoppingList(any(CompareShoppingListRequest.class))).thenThrow(e),
						() -> web.post().uri("/api/pricing/compare").contentType(MediaType.APPLICATION_JSON)
								.bodyValue("{\"items\":[],\"region\":\"x\"}").exchange()),
				new Endpoint("match", e -> when(priceStub.matchItem(any(MatchItemRequest.class))).thenThrow(e),
						() -> web.get().uri("/api/pricing/match?term=a&itemId=b").exchange()),
				new Endpoint("products", e -> when(productStub.searchProducts(any(SearchProductsRequest.class))).thenThrow(e),
						() -> web.get().uri("/api/pricing/products?term=a&itemId=b").exchange()),
				new Endpoint("suggest", e -> when(productStub.suggestProducts(any(SuggestProductsRequest.class))).thenThrow(e),
						() -> web.get().uri("/api/pricing/suggest?q=mi").exchange()),
				new Endpoint("selectProduct", e -> when(productStub.selectProduct(any(SelectProductRequest.class))).thenThrow(e),
						() -> web.put().uri("/api/pricing/items/i/product").contentType(MediaType.APPLICATION_JSON)
								.bodyValue("{\"productKey\":\"k\"}").exchange()),
				new Endpoint("storeProducts", e -> when(chainStub.listProducts(any(ListChainProductsRequest.class))).thenThrow(e),
						() -> web.get().uri("/api/pricing/stores/paknsave/products").exchange()));
	}

	private void assertMaps(Status status, int http) {
		for (Endpoint endpoint : endpoints()) {
			endpoint.failWith().accept(status.withDescription("boom from pricing").asRuntimeException());
			endpoint.call().get().expectStatus().isEqualTo(http)
					.expectBody()
					.jsonPath("$.message").isEqualTo("boom from pricing")
					.jsonPath("$.error").isEqualTo("boom from pricing");
		}
	}

	@Test
	void invalidArgumentIs422() {
		assertMaps(Status.INVALID_ARGUMENT, 422);
	}

	@Test
	void notFoundIs404() {
		assertMaps(Status.NOT_FOUND, 404);
	}

	@Test
	void unavailableIs503() {
		assertMaps(Status.UNAVAILABLE, 503);
	}

	@Test
	void deadlineExceededIs504() {
		assertMaps(Status.DEADLINE_EXCEEDED, 504);
	}

	@Test
	void anyOtherStatusIs500WithoutLeakingTheInternalMessage() {
		for (Endpoint endpoint : endpoints()) {
			endpoint.failWith().accept(Status.INTERNAL.withDescription("secret stack detail").asRuntimeException());
			endpoint.call().get().expectStatus().isEqualTo(500)
					.expectBody().jsonPath("$.message").isEqualTo("pricing request failed");
		}
	}
}
