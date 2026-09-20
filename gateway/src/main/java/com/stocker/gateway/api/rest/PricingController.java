package com.stocker.gateway.api.rest;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import com.stocker.pricing.api.grpc.v1.ShoppingListItem;
import com.stocker.pricing.api.grpc.v1.StoreTotal;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST-in / gRPC-out translation for price search and shopping-list comparison. Search results
 * already share the caller-supplied itemId (see pricing's PriceSearchService), so no cross-chain
 * reconciliation is needed there — just sort ascending so the cheapest option is first. Compare's
 * "outside service area" gRPC error (Status.INVALID_ARGUMENT, raised by pricing's
 * ShoppingListComparisonService) is translated to an HTTP 422 with the description as the body.
 */
@RestController
@RequestMapping("/api/pricing")
public class PricingController {

	private final PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceRecordServiceBlockingStub;

	public PricingController(PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceRecordServiceBlockingStub) {
		this.priceRecordServiceBlockingStub = priceRecordServiceBlockingStub;
	}

	@GetMapping("/search")
	public Mono<SearchResultResponse> search(
			@RequestParam String term,
			@RequestParam String itemId,
			@RequestParam(required = false) List<String> storeUrls,
			@RequestParam(required = false) String category) {
		SearchRequest request = SearchRequest.newBuilder()
				.setSearchTerm(term)
				.setItemId(itemId)
				.addAllStoreUrls(storeUrls == null ? List.of() : storeUrls)
				.setCategory(category == null ? "" : category)
				.build();
		// Blocking stub call, offloaded off the WebFlux/Netty event loop - never block it directly.
		return Mono.fromCallable(() -> priceRecordServiceBlockingStub.search(request))
				.subscribeOn(Schedulers.boundedElastic())
				.map(PricingController::toResponse);
	}

	@PostMapping("/compare")
	public Mono<CompareShoppingListResultResponse> compare(@RequestBody CompareShoppingListRequestBody body) {
		CompareShoppingListRequest.Builder request = CompareShoppingListRequest.newBuilder()
				.setRegion(body.region() == null ? "" : body.region())
				.setSelectedChainId(body.selectedChainId() == null ? "" : body.selectedChainId());
		if (body.items() != null) {
			body.items().forEach(item -> request.addItems(ShoppingListItem.newBuilder()
					.setItemId(item.itemId())
					.setQuantity(item.quantity())
					.build()));
		}
		// Blocking stub call, offloaded off the WebFlux/Netty event loop - never block it directly.
		return Mono.fromCallable(() -> priceRecordServiceBlockingStub.compareShoppingList(request.build()))
				.subscribeOn(Schedulers.boundedElastic())
				.map(PricingController::toCompareResponse)
				.onErrorResume(StatusRuntimeException.class, PricingController::mapServiceAreaError);
	}

	private static Mono<CompareShoppingListResultResponse> mapServiceAreaError(StatusRuntimeException ex) {
		if (ex.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
			return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, ex.getStatus().getDescription()));
		}
		return Mono.error(ex);
	}

	/**
	 * Scoped to this controller only (not a global @ControllerAdvice). WebFlux's default error
	 * body omits ResponseStatusException.getReason() unless server.error.include-message is set
	 * globally - this puts the message in the body without that repo-wide config change.
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorResponseBody> handleServiceAreaError(ResponseStatusException ex) {
		return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponseBody(ex.getReason()));
	}

	public record ErrorResponseBody(String error) {
	}

	private static CompareShoppingListResultResponse toCompareResponse(CompareShoppingListResponse response) {
		List<StoreTotalView> storeTotals = response.getStoreTotalsList().stream()
				.map(PricingController::toStoreTotalView)
				.toList();
		return new CompareShoppingListResultResponse(
				storeTotals, response.getCheapestChainId(), response.getEffectiveSelectedChainId());
	}

	private static StoreTotalView toStoreTotalView(StoreTotal storeTotal) {
		return new StoreTotalView(
				storeTotal.getChainId(),
				storeTotal.getTotalAmount(),
				storeTotal.getCurrency(),
				storeTotal.getItemsPriced(),
				storeTotal.getUnavailableItemIdsList(),
				storeTotal.getSavingsAmount());
	}

	public record CompareShoppingListRequestBody(
			List<ShoppingListItemBody> items, String region, String selectedChainId) {
	}

	public record ShoppingListItemBody(String itemId, int quantity) {
	}

	public record CompareShoppingListResultResponse(
			List<StoreTotalView> storeTotals, String cheapestChainId, String selectedChainId) {
	}

	public record StoreTotalView(String chainId, double totalAmount, String currency, int itemsPriced,
			List<String> unavailableItemIds, double savingsAmount) {
	}

	private static SearchResultResponse toResponse(SearchResponse response) {
		List<PriceRecordView> records = response.getPriceRecordsList().stream()
				.map(PricingController::toView)
				.sorted(Comparator.comparingDouble(PriceRecordView::priceAmount))
				.toList();
		return new SearchResultResponse(records);
	}

	private static PriceRecordView toView(com.stocker.pricing.api.grpc.v1.PriceRecord record) {
		return new PriceRecordView(
				record.getId(),
				record.getItemId(),
				record.getStoreId(),
				record.getChainId(),
				record.getChannel(),
				record.getPriceAmount(),
				record.getCurrency(),
				record.getPromoFlag(),
				toIsoStringOrNull(record.getCapturedAt()));
	}

	private static String toIsoStringOrNull(Timestamp timestamp) {
		if (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0) {
			return null;
		}
		return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).toString();
	}

	public record SearchResultResponse(List<PriceRecordView> priceRecords) {
	}

	public record PriceRecordView(String id, String itemId, String storeId, String chainId, String channel,
			double priceAmount, String currency, boolean promoFlag, String capturedAt) {
	}
}
