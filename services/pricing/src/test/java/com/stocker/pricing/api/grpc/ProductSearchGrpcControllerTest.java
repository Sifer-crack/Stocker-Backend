package com.stocker.pricing.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stocker.pricing.api.grpc.v1.SearchProductsRequest;
import com.stocker.pricing.api.grpc.v1.SearchProductsResponse;
import com.stocker.pricing.api.grpc.v1.SelectProductRequest;
import com.stocker.pricing.api.grpc.v1.SelectProductResponse;
import com.stocker.pricing.service.search.ProductSearchService;
import com.stocker.pricing.service.search.ProductSuggestService;
import com.stocker.pricing.service.search.ProductSuggestService.Suggestion;
import com.stocker.pricing.api.grpc.v1.SuggestProductsRequest;
import com.stocker.pricing.api.grpc.v1.SuggestProductsResponse;
import com.stocker.pricing.service.search.ProductSearchService.ChainPrice;
import com.stocker.pricing.service.search.ProductSearchService.Product;
import com.stocker.pricing.service.search.ProductSearchService.Query;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import com.stocker.pricing.service.search.ProductSearchService.SelectedProduct;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductSearchGrpcControllerTest {

	private ProductSearchService service;
	private ProductSuggestService suggestService;
	private ProductSearchGrpcController controller;

	@BeforeEach
	void setUp() {
		service = mock(ProductSearchService.class);
		suggestService = mock(ProductSuggestService.class);
		controller = new ProductSearchGrpcController(service, suggestService);
	}

	private static final class Capture<T> implements StreamObserver<T> {
		final List<T> values = new ArrayList<>();
		Throwable error;
		boolean completed;

		@Override public void onNext(T value) { values.add(value); }
		@Override public void onError(Throwable t) { error = t; }
		@Override public void onCompleted() { completed = true; }
	}

	private static SearchResult result() {
		OffsetDateTime at = OffsetDateTime.of(2026, 10, 19, 14, 0, 0, 0, ZoneOffset.UTC);
		Product eggs = new Product("pams-free-range-eggs|size-7|12each", "Pams Free Range Eggs Size 7 12 Pack", "Pams",
				"eggs", "12 pack", "7", "each", new BigDecimal("0.45"), "paknsave", Map.of(
						"paknsave", new ChainPrice(new BigDecimal("5.40"), new BigDecimal("0.45"), false, "paknsave:", at, false),
						"woolworths", new ChainPrice(new BigDecimal("5.90"), null, true, "woolworths:", at, true)));
		Product bare = new Product("bananas", "Bananas", null, null, null, null, null, null, "newworld",
				Map.of("newworld", new ChainPrice(new BigDecimal("3.50"), null, false, "newworld:", at, false)));
		return new SearchResult(new Query("eggs size 7", List.of("egg"), null, null, "7", "eggs"), "canonical", true,
				List.of(eggs), List.of(bare), "found", Map.of("paknsave", "pending", "newworld", "done"));
	}

	@Test
	void searchTranslatesTheResultAndKeepsAbsentOptionalFieldsUnset() {
		when(service.search("eggs size 7", "item-1", "", 5)).thenReturn(result());
		Capture<SearchProductsResponse> out = new Capture<>();

		controller.searchProducts(SearchProductsRequest.newBuilder().setTerm("eggs size 7").setItemId("item-1").setLimit(5).build(), out);

		assertThat(out.completed).isTrue();
		SearchProductsResponse response = out.values.get(0);
		assertThat(response.getMatchMethod()).isEqualTo("canonical");
		assertThat(response.getStale()).isTrue();
		assertThat(response.getStatus()).isEqualTo("found");
		assertThat(response.getRefreshMap()).containsOnly(Map.entry("paknsave", "pending"), Map.entry("newworld", "done"));
		assertThat(response.getQuery().getGrade()).isEqualTo("7");
		assertThat(response.getQuery().hasSize()).isFalse();
		assertThat(response.getQuery().hasPackCount()).isFalse();
		assertThat(response.getQuery().getCategory()).isEqualTo("eggs");
		var eggs = response.getProducts(0);
		assertThat(eggs.getProductKey()).isEqualTo("pams-free-range-eggs|size-7|12each");
		assertThat(eggs.getBrand()).isEqualTo("Pams");
		assertThat(eggs.getUnit()).isEqualTo("each");
		assertThat(eggs.getUnitPrice()).isEqualTo(0.45);
		assertThat(eggs.getPricesMap()).containsOnlyKeys("paknsave", "woolworths");
		assertThat(eggs.getPricesOrThrow("paknsave").getCapturedAt().getSeconds())
				.isEqualTo(OffsetDateTime.of(2026, 10, 19, 14, 0, 0, 0, ZoneOffset.UTC).toEpochSecond());
		assertThat(eggs.getPricesOrThrow("woolworths").hasUnitPrice()).isFalse();
		assertThat(eggs.getPricesOrThrow("woolworths").getStale()).isTrue();
		var bare = response.getAlternatives(0);
		assertThat(bare.hasBrand()).isFalse();
		assertThat(bare.hasPackSize()).isFalse();
		assertThat(bare.hasUnitPrice()).isFalse();
	}

	@Test
	void blankTermOrItemIdIsInvalidArgument() {
		Capture<SearchProductsResponse> out = new Capture<>();
		controller.searchProducts(SearchProductsRequest.newBuilder().setTerm(" ").setItemId("i").build(), out);
		assertThat(((StatusRuntimeException) out.error).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);

		Capture<SearchProductsResponse> out2 = new Capture<>();
		controller.searchProducts(SearchProductsRequest.newBuilder().setTerm("milk").build(), out2);
		assertThat(((StatusRuntimeException) out2.error).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		verifyNoInteractions(service);
	}

	@Test
	void selectReturnsTheStoredChains() {
		when(service.selectProduct("item-1", "k|1l")).thenReturn(new SelectedProduct("item-1", "k|1l", List.of("newworld")));
		Capture<SelectProductResponse> out = new Capture<>();

		controller.selectProduct(SelectProductRequest.newBuilder().setItemId("item-1").setProductKey("k|1l").build(), out);

		assertThat(out.values.get(0).getChainsList()).containsExactly("newworld");
		assertThat(out.values.get(0).getItemId()).isEqualTo("item-1");
	}

	@Test
	void unknownKeyIsNotFound() {
		when(service.selectProduct(anyString(), anyString())).thenThrow(new ProductSearchService.ProductNotFoundException("zzz"));
		Capture<SelectProductResponse> out = new Capture<>();

		controller.selectProduct(SelectProductRequest.newBuilder().setItemId("item-1").setProductKey("zzz").build(), out);

		assertThat(((StatusRuntimeException) out.error).getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
	}

	@Test
	void blankSelectArgumentsAreInvalidArgument() {
		Capture<SelectProductResponse> out = new Capture<>();
		controller.selectProduct(SelectProductRequest.newBuilder().setItemId("item-1").setProductKey(" ").build(), out);
		assertThat(((StatusRuntimeException) out.error).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		verifyNoInteractions(service);
	}

	@Test
	void aCancelledCallDoesNoWorkAndGetsNoReply() {
		Context.CancellableContext context = Context.current().withCancellation();
		context.cancel(new RuntimeException("client went away"));
		@SuppressWarnings("unchecked")
		StreamObserver<SearchProductsResponse> out = mock(StreamObserver.class);
		@SuppressWarnings("unchecked")
		StreamObserver<SelectProductResponse> out2 = mock(StreamObserver.class);

		context.run(() -> {
			controller.searchProducts(SearchProductsRequest.newBuilder().setTerm("milk").setItemId("i").build(), out);
			controller.selectProduct(SelectProductRequest.newBuilder().setItemId("i").setProductKey("k").build(), out2);
		});

		verifyNoInteractions(service, out, out2);
	}

	@Test
	void aCallCancelledWhileSearchingGetsNoReply() {
		Context.CancellableContext context = Context.current().withCancellation();
		when(service.search(anyString(), anyString(), any(), anyInt())).thenAnswer(inv -> {
			context.cancel(new RuntimeException("client went away"));
			return result();
		});
		@SuppressWarnings("unchecked")
		StreamObserver<SearchProductsResponse> out = mock(StreamObserver.class);

		context.run(() -> controller.searchProducts(SearchProductsRequest.newBuilder().setTerm("milk").setItemId("i").build(), out));

		verify(service).search(eq("milk"), eq("i"), any(), anyInt());
		verifyNoInteractions(out);
	}

	@Test
	void suggestTranslatesSuggestionsAndLeavesAbsentOptionalFieldsUnset() {
		when(suggestService.suggest("eg", 8)).thenReturn(List.of(
				new Suggestion("Eggs", "eggs", 3, List.of("newworld", "paknsave", "woolworths"), "12 pack", 4),
				new Suggestion("Egg Fried Rice", null, 1, List.of("newworld"), null, 1)));
		Capture<SuggestProductsResponse> out = new Capture<>();

		controller.suggestProducts(SuggestProductsRequest.newBuilder().setQ("eg").setLimit(8).build(), out);

		assertThat(out.completed).isTrue();
		SuggestProductsResponse response = out.values.get(0);
		assertThat(response.getSuggestionsCount()).isEqualTo(2);
		var eggs = response.getSuggestions(0);
		assertThat(eggs.getText()).isEqualTo("Eggs");
		assertThat(eggs.hasCanonicalKey()).isTrue();
		assertThat(eggs.getCanonicalKey()).isEqualTo("eggs");
		assertThat(eggs.getChainCount()).isEqualTo(3);
		assertThat(eggs.getChainsList()).containsExactly("newworld", "paknsave", "woolworths");
		assertThat(eggs.getSampleSize()).isEqualTo("12 pack");
		assertThat(eggs.getProductCount()).isEqualTo(4);
		var dish = response.getSuggestions(1);
		assertThat(dish.hasCanonicalKey()).isFalse();
		assertThat(dish.hasSampleSize()).isFalse();
	}

	@Test
	void suggestAcceptsABlankQueryAndPassesItOn() {
		when(suggestService.suggest("", 0)).thenReturn(List.of());
		Capture<SuggestProductsResponse> out = new Capture<>();

		controller.suggestProducts(SuggestProductsRequest.newBuilder().build(), out);

		assertThat(out.error).isNull();
		assertThat(out.completed).isTrue();
		assertThat(out.values.get(0).getSuggestionsCount()).isZero();
		verify(suggestService).suggest("", 0);
	}

	@Test
	void suggestStopsWhenTheCallerHasAlreadyGone() throws Exception {
		Capture<SuggestProductsResponse> out = new Capture<>();

		Context.CancellableContext ctx = Context.current().withCancellation();
		ctx.cancel(new RuntimeException("client gone"));
		ctx.run(() -> controller.suggestProducts(SuggestProductsRequest.newBuilder().setQ("eg").build(), out));

		assertThat(out.values).isEmpty();
		verifyNoInteractions(suggestService);
	}
}
