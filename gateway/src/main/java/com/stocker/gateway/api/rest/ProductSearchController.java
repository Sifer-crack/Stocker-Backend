package com.stocker.gateway.api.rest;

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
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST-in / gRPC-out for the merged-product search (docs/features/price-search-v2.md): {@code GET /api/pricing/products}
 * and {@code PUT /api/pricing/items/{itemId}/product}. Prices are keyed by lower-case chain id; absent optional fields
 * are JSON null. INVALID_ARGUMENT from pricing becomes 422 and NOT_FOUND 404, both as {@code {"error": ...}}.
 *
 * <p>Cancellation works as in {@link PricingController}: a disposed Mono interrupts the boundedElastic thread blocked
 * in the gRPC stub, which cancels the RPC (ProductSearchControllerCancellationTest).
 */
@RestController
@RequestMapping("/api/pricing")
public class ProductSearchController {

	private final ProductSearchGrpc.ProductSearchBlockingStub stub;

	public ProductSearchController(ProductSearchGrpc.ProductSearchBlockingStub stub) {
		this.stub = stub;
	}

	/** A missing or blank term / itemId reaches pricing as an empty string and comes back as 422. */
	@GetMapping("/products")
	public Mono<ProductSearchBody> products(
			@RequestParam(required = false) String term,
			@RequestParam(required = false) String itemId,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) Integer limit) {
		SearchProductsRequest request = SearchProductsRequest.newBuilder()
				.setTerm(term == null ? "" : term)
				.setItemId(itemId == null ? "" : itemId)
				.setCategory(category == null ? "" : category)
				.setLimit(limit == null ? 0 : limit)
				.build();
		// Blocking stub call, offloaded off the WebFlux/Netty event loop - never block it directly.
		return Mono.fromCallable(() -> stub.searchProducts(request))
				.subscribeOn(Schedulers.boundedElastic())
				.map(ProductSearchController::toBody)
				.onErrorResume(StatusRuntimeException.class, ProductSearchController::mapError);
	}

	@PutMapping("/items/{itemId}/product")
	public Mono<SelectedProductBody> selectProduct(@PathVariable String itemId, @RequestBody SelectProductBody body) {
		SelectProductRequest request = SelectProductRequest.newBuilder()
				.setItemId(itemId)
				.setProductKey(body == null || body.productKey() == null ? "" : body.productKey())
				.build();
		return Mono.fromCallable(() -> stub.selectProduct(request))
				.subscribeOn(Schedulers.boundedElastic())
				.map(ProductSearchController::toBody)
				.onErrorResume(StatusRuntimeException.class, ProductSearchController::mapError);
	}

	private static <T> Mono<T> mapError(StatusRuntimeException ex) {
		return switch (ex.getStatus().getCode()) {
			case INVALID_ARGUMENT -> Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus().getDescription()));
			case NOT_FOUND -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getStatus().getDescription()));
			default -> Mono.error(ex);
		};
	}

	/** Controller-scoped, like PricingController: puts the message in the body as {@code {"error": ...}}. */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorBody> handleStatus(ResponseStatusException ex) {
		return ResponseEntity.status(ex.getStatusCode()).body(new ErrorBody(ex.getReason()));
	}

	public record ErrorBody(String error) {
	}

	public record SelectProductBody(String productKey) {
	}

	public record SelectedProductBody(String itemId, String productKey, List<String> chains) {
	}

	public record QueryBody(String term, List<String> words, String size, Integer packCount, String grade, String category) {
	}

	public record ChainPriceBody(double price, Double unitPrice, boolean promo, String storeId, String capturedAt,
			boolean stale) {
	}

	public record ProductBody(String productKey, String name, String brand, String category, String packSize,
			String grade, String unit, Double unitPrice, String cheapestChainId, Map<String, ChainPriceBody> prices) {
	}

	public record ProductSearchBody(QueryBody query, String matchMethod, String status, boolean stale,
			Map<String, String> refresh, List<ProductBody> products, List<ProductBody> alternatives) {
	}

	private static SelectedProductBody toBody(SelectProductResponse response) {
		return new SelectedProductBody(response.getItemId(), response.getProductKey(), response.getChainsList());
	}

	private static ProductSearchBody toBody(SearchProductsResponse response) {
		ParsedQuery q = response.getQuery();
		QueryBody query = new QueryBody(q.getTerm(), q.getWordsList(), q.hasSize() ? q.getSize() : null,
				q.hasPackCount() ? q.getPackCount() : null, q.hasGrade() ? q.getGrade() : null,
				q.hasCategory() ? q.getCategory() : null);
		Map<String, String> refresh = new java.util.TreeMap<>();
		response.getRefreshMap().forEach((chain, state) -> refresh.put(chain.toLowerCase(java.util.Locale.ROOT), state));
		return new ProductSearchBody(query, response.getMatchMethod(), response.getStatus(), response.getStale(), refresh,
				response.getProductsList().stream().map(ProductSearchController::toBody).toList(),
				response.getAlternativesList().stream().map(ProductSearchController::toBody).toList());
	}

	private static ProductBody toBody(Product p) {
		Map<String, ChainPriceBody> prices = new LinkedHashMap<>();
		p.getPricesMap().entrySet().stream().sorted(Map.Entry.comparingByKey())
				.forEach(entry -> prices.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), toBody(entry.getValue())));
		return new ProductBody(p.getProductKey(), p.getName(), p.hasBrand() ? p.getBrand() : null,
				p.hasCategory() ? p.getCategory() : null, p.hasPackSize() ? p.getPackSize() : null,
				p.hasGrade() ? p.getGrade() : null, p.hasUnit() ? p.getUnit() : null,
				p.hasUnitPrice() ? p.getUnitPrice() : null, p.getCheapestChainId().toLowerCase(java.util.Locale.ROOT), prices);
	}

	private static ChainPriceBody toBody(ChainPrice c) {
		return new ChainPriceBody(c.getPrice(), c.hasUnitPrice() ? c.getUnitPrice() : null, c.getPromo(), c.getStoreId(),
				c.hasCapturedAt() ? toIso(c.getCapturedAt()) : null, c.getStale());
	}

	private static String toIso(Timestamp timestamp) {
		return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString();
	}
}
