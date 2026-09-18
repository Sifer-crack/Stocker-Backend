package com.stocker.gateway.api.rest;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * REST-in / gRPC-out translation for price search. All of a search's results already share the
 * caller-supplied itemId (see pricing's PriceSearchService), so no cross-chain reconciliation is
 * needed here — just sort ascending so the cheapest option is first.
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
