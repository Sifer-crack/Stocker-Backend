package com.stocker.gateway.api.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainPrice;
import com.stocker.pricing.api.grpc.v1.ParsedQuery;
import com.stocker.pricing.api.grpc.v1.Product;
import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import com.stocker.pricing.api.grpc.v1.SearchProductsRequest;
import com.stocker.pricing.api.grpc.v1.SearchProductsResponse;
import com.stocker.pricing.api.grpc.v1.SelectProductRequest;
import com.stocker.pricing.api.grpc.v1.SelectProductResponse;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(ProductSearchController.class)
class ProductSearchControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@MockitoBean
	private ProductSearchGrpc.ProductSearchBlockingStub stub;

	private static final Timestamp AT = Timestamp.newBuilder().setSeconds(1792418400L).build();   // 2026-10-19T14:00:00Z

	private static SearchProductsResponse eggsResponse() {
		return SearchProductsResponse.newBuilder()
				.setQuery(ParsedQuery.newBuilder().setTerm("eggs size 7").addWords("egg").setGrade("7").setCategory("eggs"))
				.setMatchMethod("canonical")
				.addProducts(Product.newBuilder()
						.setProductKey("pams-free-range-eggs|size-7|12each").setName("Pams Free Range Eggs Size 7 12 Pack")
						.setBrand("Pams").setCategory("eggs").setPackSize("12 pack").setGrade("7").setUnit("each")
						.setUnitPrice(0.45).setCheapestChainId("paknsave")
						.putPrices("woolworths", ChainPrice.newBuilder().setPrice(5.90).setPromo(true)
								.setStoreId("woolworths:").setCapturedAt(AT).build())
						.putPrices("paknsave", ChainPrice.newBuilder().setPrice(5.40).setUnitPrice(0.45)
								.setStoreId("paknsave:").setCapturedAt(AT).setStale(true).build()))
				.addAlternatives(Product.newBuilder().setProductKey("bananas").setName("Bananas").setCheapestChainId("newworld")
						.putPrices("newworld", ChainPrice.newBuilder().setPrice(3.50).setStoreId("newworld:").setCapturedAt(AT).build()))
				.build();
	}

	@Test
	void mapsTheContractShapeWithLowerCaseChainKeys() {
		when(stub.searchProducts(any(SearchProductsRequest.class))).thenReturn(eggsResponse());

		webTestClient.get().uri("/api/pricing/products?term={t}&itemId=item-1&limit=5", "eggs size 7")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.query.term").isEqualTo("eggs size 7")
				.jsonPath("$.query.words[0]").isEqualTo("egg")
				.jsonPath("$.query.grade").isEqualTo("7")
				.jsonPath("$.query.category").isEqualTo("eggs")
				.jsonPath("$.matchMethod").isEqualTo("canonical")
				.jsonPath("$.stale").isEqualTo(false)
				.jsonPath("$.products[0].productKey").isEqualTo("pams-free-range-eggs|size-7|12each")
				.jsonPath("$.products[0].brand").isEqualTo("Pams")
				.jsonPath("$.products[0].packSize").isEqualTo("12 pack")
				.jsonPath("$.products[0].unit").isEqualTo("each")
				.jsonPath("$.products[0].unitPrice").isEqualTo(0.45)
				.jsonPath("$.products[0].cheapestChainId").isEqualTo("paknsave")
				.jsonPath("$.products[0].prices.paknsave.price").isEqualTo(5.40)
				.jsonPath("$.products[0].prices.paknsave.unitPrice").isEqualTo(0.45)
				.jsonPath("$.products[0].prices.paknsave.promo").isEqualTo(false)
				.jsonPath("$.products[0].prices.paknsave.storeId").isEqualTo("paknsave:")
				.jsonPath("$.products[0].prices.paknsave.capturedAt").isEqualTo("2026-10-19T14:00:00Z")
				.jsonPath("$.products[0].prices.paknsave.stale").isEqualTo(true)
				.jsonPath("$.products[0].prices.woolworths.promo").isEqualTo(true)
				.jsonPath("$.products[0].prices.WOOLWORTHS").doesNotExist()
				.jsonPath("$.alternatives[0].productKey").isEqualTo("bananas");
		verify(stub).searchProducts(argThat((SearchProductsRequest r) -> r.getTerm().equals("eggs size 7")
				&& r.getItemId().equals("item-1") && r.getLimit() == 5 && r.getCategory().isEmpty()));
	}

	@Test
	void absentOptionalFieldsAreExplicitNulls() {
		when(stub.searchProducts(any(SearchProductsRequest.class))).thenReturn(eggsResponse());

		webTestClient.get().uri("/api/pricing/products?term=eggs&itemId=item-1")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.query.size").value(v -> org.assertj.core.api.Assertions.assertThat(v).isNull())
				.jsonPath("$.query.packCount").value(v -> org.assertj.core.api.Assertions.assertThat(v).isNull())
				.jsonPath("$.alternatives[0].brand").value(v -> org.assertj.core.api.Assertions.assertThat(v).isNull())
				.jsonPath("$.alternatives[0].unitPrice").value(v -> org.assertj.core.api.Assertions.assertThat(v).isNull())
				.jsonPath("$.alternatives[0].packSize").value(v -> org.assertj.core.api.Assertions.assertThat(v).isNull())
				.jsonPath("$.alternatives[0].prices.newworld.unitPrice").value(v -> org.assertj.core.api.Assertions.assertThat(v).isNull());
		// the keys are present (null), not omitted
		webTestClient.get().uri("/api/pricing/products?term=eggs&itemId=item-1").exchange().expectBody(String.class)
				.value(body -> org.assertj.core.api.Assertions.assertThat(body)
						.contains("\"size\":null").contains("\"packCount\":null").contains("\"brand\":null"));
	}

	@Test
	void packCountAndSizeAreReturnedWhenTheTermStatesThem() {
		when(stub.searchProducts(any(SearchProductsRequest.class))).thenReturn(SearchProductsResponse.newBuilder()
				.setQuery(ParsedQuery.newBuilder().setTerm("milk 2L 12 pack").setSize("2L").setPackCount(12)).setMatchMethod("lexical").build());

		webTestClient.get().uri("/api/pricing/products?term=milk%202L%2012%20pack&itemId=i")
				.exchange().expectBody()
				.jsonPath("$.query.size").isEqualTo("2L")
				.jsonPath("$.query.packCount").isEqualTo(12);
	}

	@Test
	void nothingStoredYetIsAnEmptyOkResponse() {
		when(stub.searchProducts(any(SearchProductsRequest.class))).thenReturn(SearchProductsResponse.newBuilder()
				.setQuery(ParsedQuery.newBuilder().setTerm("dragonfruit")).setMatchMethod("lexical").build());

		webTestClient.get().uri("/api/pricing/products?term=dragonfruit&itemId=i")
				.exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.products.length()").isEqualTo(0)
				.jsonPath("$.alternatives.length()").isEqualTo(0);
	}

	@Test
	void invalidArgumentBecomes422WithTheMessage() {
		when(stub.searchProducts(any(SearchProductsRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("term and itemId are required").asRuntimeException());

		webTestClient.get().uri("/api/pricing/products?term=%20&itemId=i")
				.exchange().expectStatus().isEqualTo(422).expectBody()
				.jsonPath("$.error").isEqualTo("term and itemId are required");
	}

	@Test
	void aMissingTermIsAlso422() {
		when(stub.searchProducts(any(SearchProductsRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("term and itemId are required").asRuntimeException());

		webTestClient.get().uri("/api/pricing/products?itemId=i").exchange().expectStatus().isEqualTo(422);
	}

	@Test
	void selectMapsTheResponse() {
		when(stub.selectProduct(any(SelectProductRequest.class))).thenReturn(SelectProductResponse.newBuilder()
				.setItemId("item-1").setProductKey("pams-free-range-eggs|size-7|12each").addChains("paknsave").addChains("woolworths").build());

		webTestClient.put().uri("/api/pricing/items/item-1/product").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"productKey\":\"pams-free-range-eggs|size-7|12each\"}")
				.exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.itemId").isEqualTo("item-1")
				.jsonPath("$.productKey").isEqualTo("pams-free-range-eggs|size-7|12each")
				.jsonPath("$.chains[0]").isEqualTo("paknsave")
				.jsonPath("$.chains[1]").isEqualTo("woolworths");
		verify(stub).selectProduct(argThat((SelectProductRequest r) -> r.getItemId().equals("item-1")
				&& r.getProductKey().equals("pams-free-range-eggs|size-7|12each")));
	}

	@Test
	void selectUnknownKeyIs404WithTheMessage() {
		when(stub.selectProduct(any(SelectProductRequest.class)))
				.thenThrow(Status.NOT_FOUND.withDescription("No product with key 'x' is known").asRuntimeException());

		webTestClient.put().uri("/api/pricing/items/item-1/product").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"productKey\":\"x\"}")
				.exchange().expectStatus().isNotFound().expectBody()
				.jsonPath("$.error").isEqualTo("No product with key 'x' is known");
	}

	@Test
	void selectWithABlankKeyIs422() {
		when(stub.selectProduct(any(SelectProductRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("itemId and productKey are required").asRuntimeException());

		webTestClient.put().uri("/api/pricing/items/item-1/product").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"productKey\":\"\"}")
				.exchange().expectStatus().isEqualTo(422).expectBody()
				.jsonPath("$.error").isEqualTo("itemId and productKey are required");
	}

	@Test
	void selectWithoutABodyKeyIs422() {
		when(stub.selectProduct(any(SelectProductRequest.class)))
				.thenThrow(Status.INVALID_ARGUMENT.withDescription("itemId and productKey are required").asRuntimeException());

		webTestClient.put().uri("/api/pricing/items/item-1/product").contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{}").exchange().expectStatus().isEqualTo(422);
	}
}
