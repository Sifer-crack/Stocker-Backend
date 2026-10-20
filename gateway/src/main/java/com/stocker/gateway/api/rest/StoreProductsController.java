package com.stocker.gateway.api.rest;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import com.stocker.pricing.api.grpc.v1.ChainProduct;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST-in / gRPC-out: the products stored for one supermarket chain, for browsing a favorite store.
 * Database-backed in pricing; it never causes a supermarket request. The frontend adds an item to the list
 * with {@code POST /api/shopping/items}.
 */
@RestController
@RequestMapping("/api/pricing/stores")
public class StoreProductsController {

	private final ChainCatalogGrpc.ChainCatalogBlockingStub chainCatalogBlockingStub;

	public StoreProductsController(ChainCatalogGrpc.ChainCatalogBlockingStub chainCatalogBlockingStub) {
		this.chainCatalogBlockingStub = chainCatalogBlockingStub;
	}

	@GetMapping("/{chainId}/products")
	public Mono<StoreProductsResponse> products(@PathVariable String chainId,
			@RequestParam(required = false, defaultValue = "") String query,
			@RequestParam(required = false, defaultValue = "0") int limit) {
		ListChainProductsRequest request = ListChainProductsRequest.newBuilder()
				.setChainId(chainId).setQuery(query).setLimit(limit).build();
		// Blocking stub call, offloaded off the WebFlux/Netty event loop - never block it directly.
		return Mono.fromCallable(() -> chainCatalogBlockingStub.listProducts(request))
				.subscribeOn(Schedulers.boundedElastic())
				.map(StoreProductsController::toResponse);
	}

	private static StoreProductsResponse toResponse(ListChainProductsResponse response) {
		return new StoreProductsResponse(response.getProductsList().stream().map(StoreProductsController::toView).toList());
	}

	private static StoreProductView toView(ChainProduct product) {
		return new StoreProductView(
				product.getItemId(),
				product.getName(),
				product.getBrand().isEmpty() ? null : product.getBrand(),
				product.getPriceAmount(),
				product.getCurrency(),
				product.getPromoFlag(),
				product.getPackSize().isEmpty() ? null : product.getPackSize(),
				toIsoStringOrNull(product.getCapturedAt()));
	}

	private static String toIsoStringOrNull(Timestamp timestamp) {
		if (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0) {
			return null;
		}
		return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString();
	}

	public record StoreProductsResponse(List<StoreProductView> products) {
	}

	/** {@code brand} and {@code packSize} are omitted (null) when unknown. */
	public record StoreProductView(String itemId, String name, String brand, double priceAmount, String currency,
			boolean promoFlag, String packSize, String capturedAt) {
	}
}
