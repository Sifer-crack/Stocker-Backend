package com.stocker.pricing.api.grpc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.FetchRequest;
import com.stocker.pricing.api.grpc.v1.FetchResponse;
import com.stocker.pricing.api.grpc.v1.PriceRecord;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.SaveRequest;
import com.stocker.pricing.api.grpc.v1.SaveResponse;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.util.StringUtils;

@GrpcService
@RequiredArgsConstructor
public class PriceRecordGrpcController extends PriceRecordServiceGrpc.PriceRecordServiceImplBase {

	private static final Logger log = LoggerFactory.getLogger(PriceRecordGrpcController.class);
	private static final Set<String> VALID_CHANNELS = Set.of("pickup", "click_and_collect");
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final PriceFetcherService priceFetcherService;
	private final PriceSearchService priceSearchService;

	@Override
	public void fetch(FetchRequest request, StreamObserver<FetchResponse> responseObserver) {
		log.info("gRPC fetch: itemId={}, storeId={}", request.getItemId(), request.getStoreId());
		List<com.stocker.pricing.model.PriceRecord> records =
				priceFetcherService.fetch(request.getItemId(), request.getStoreId());
		FetchResponse.Builder response = FetchResponse.newBuilder();
		records.stream().map(PriceRecordGrpcController::toProto).forEach(response::addPriceRecords);
		responseObserver.onNext(response.build());
		responseObserver.onCompleted();
	}

	@Override
	public void save(SaveRequest request, StreamObserver<SaveResponse> responseObserver) {
		log.info("gRPC save: itemId={}, storeId={}", request.getPriceRecord().getItemId(),
				request.getPriceRecord().getStoreId());
		PriceRecord proto = request.getPriceRecord();
		if (!request.hasPriceRecord() || !StringUtils.hasText(proto.getItemId())
				|| !StringUtils.hasText(proto.getStoreId())) {
			responseObserver.onNext(SaveResponse.newBuilder().setSaved(false).build());
			responseObserver.onCompleted();
			return;
		}
		if (!VALID_CHANNELS.contains(proto.getChannel())) {
			log.warn("gRPC save rejected: invalid channel '{}'", proto.getChannel());
			responseObserver.onNext(SaveResponse.newBuilder().setSaved(false).build());
			responseObserver.onCompleted();
			return;
		}
		if (proto.getPriceAmount() < 0) {
			log.warn("gRPC save rejected: negative price_amount {}", proto.getPriceAmount());
			responseObserver.onNext(SaveResponse.newBuilder().setSaved(false).build());
			responseObserver.onCompleted();
			return;
		}
		com.stocker.pricing.model.PriceRecord saved = priceFetcherService.save(toEntity(proto));
		responseObserver.onNext(SaveResponse.newBuilder()
				.setSaved(true)
				.setId(saved.getId() == null ? "" : saved.getId().toString())
				.build());
		responseObserver.onCompleted();
	}

	@Override
	public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
		log.info("gRPC search: searchTerm={}, itemId={}", request.getSearchTerm(), request.getItemId());
		if (!StringUtils.hasText(request.getItemId())) {
			log.warn("gRPC search rejected: itemId is required");
			responseObserver.onNext(SearchResponse.getDefaultInstance());
			responseObserver.onCompleted();
			return;
		}
		List<com.stocker.pricing.model.PriceRecord> records = priceSearchService.search(
				request.getSearchTerm(), request.getItemId(), request.getStoreUrlsList(), request.getCategory());
		SearchResponse.Builder response = SearchResponse.newBuilder();
		records.stream().map(PriceRecordGrpcController::toProto).forEach(response::addPriceRecords);
		responseObserver.onNext(response.build());
		responseObserver.onCompleted();
	}

	private static PriceRecord toProto(com.stocker.pricing.model.PriceRecord record) {
		return PriceRecord.newBuilder()
				.setId(record.getId() == null ? "" : record.getId().toString())
				.setItemId(record.getItemId())
				.setStoreId(record.getStoreId())
				.setChainId(record.getChainId())
				.setChannel(record.getChannel())
				.setPriceAmount(record.getPriceAmount() == null ? 0.0 : record.getPriceAmount().doubleValue())
				.setCurrency(record.getCurrency())
				.setPromoFlag(record.isPromoFlag())
				.setCapturedAt(toProtoTimestamp(record.getCapturedAt()))
				.setRawAttributes(record.getRawAttributes() == null ? "{}" : record.getRawAttributes().toString())
				.setCreatedAt(toProtoTimestamp(record.getCreatedAt()))
				.build();
	}

	private static com.stocker.pricing.model.PriceRecord toEntity(PriceRecord record) {
		OffsetDateTime capturedAt = toOffsetDateTime(record.getCapturedAt());
		return com.stocker.pricing.model.PriceRecord.builder()
				.itemId(record.getItemId())
				.storeId(record.getStoreId())
				.chainId(record.getChainId())
				.channel(record.getChannel())
				.priceAmount(java.math.BigDecimal.valueOf(record.getPriceAmount()))
				.currency(record.getCurrency())
				.promoFlag(record.getPromoFlag())
				.capturedAt(capturedAt != null ? capturedAt : OffsetDateTime.now(ZoneOffset.UTC))
				.rawAttributes(parseRawAttributes(record.getRawAttributes()))
				.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
				.build();
	}

	private static Map<String, Object> parseRawAttributes(String rawAttributesJson) {
		if (!StringUtils.hasText(rawAttributesJson)) {
			return Map.of();
		}
		try {
			return OBJECT_MAPPER.readValue(rawAttributesJson, new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception e) {
			log.warn("Failed to parse raw_attributes JSON, defaulting to empty map: {}", e.getMessage());
			return Map.of();
		}
	}

	private static Timestamp toProtoTimestamp(OffsetDateTime dateTime) {
		return dateTime == null
				? Timestamp.getDefaultInstance()
				: Timestamp.newBuilder()
						.setSeconds(dateTime.toEpochSecond())
						.setNanos(dateTime.getNano())
						.build();
	}

	private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
			return null;
		}
		return OffsetDateTime.ofInstant(
				java.time.Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()), ZoneOffset.UTC);
	}
}
