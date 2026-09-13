package com.stocker.pricing.api.grpc;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.FetchRequest;
import com.stocker.pricing.api.grpc.v1.FetchResponse;
import com.stocker.pricing.api.grpc.v1.PriceRecord;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.SaveRequest;
import com.stocker.pricing.api.grpc.v1.SaveResponse;
import com.stocker.pricing.service.PriceFetcherService;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

// TODO: this service is never served — the Spring gRPC server is not configured (see build.gradle)
// and Spring gRPC registers @GrpcService beans, not @Controller. Switch to @GrpcService once the
// server starter is added.
@Controller
@RequiredArgsConstructor
public class PriceRecordGrpcController extends PriceRecordServiceGrpc.PriceRecordServiceImplBase {

	private static final Logger log = LoggerFactory.getLogger(PriceRecordGrpcController.class);

	private final PriceFetcherService priceFetcherService;

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
		if (!request.hasPriceRecord() || !StringUtils.hasText(request.getPriceRecord().getItemId())
				|| !StringUtils.hasText(request.getPriceRecord().getStoreId())) {
			responseObserver.onNext(SaveResponse.newBuilder().setSaved(false).build());
			responseObserver.onCompleted();
			return;
		}
		// TODO: validate before persisting — channel must be one of the Flyway CHECK values
		// ('pickup','click_and_collect'), price_amount must be >= 0 (DB CHECK), and captured_at must
		// be non-zero (column is NOT NULL).
		com.stocker.pricing.model.PriceRecord saved =
				priceFetcherService.save(toEntity(request.getPriceRecord()));
		responseObserver.onNext(SaveResponse.newBuilder()
				.setSaved(true)
				.setId(saved.getId() == null ? "" : saved.getId().toString())
				.build());
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
		// TODO: raw_attributes is never copied from the request (proto field is a JSON string) and
		// the entity column is NOT NULL — parse it into Map<String,Object> (or default to Map.of()).
		// TODO: captured_at may be the zero proto timestamp -> null -> NOT NULL violation; default it.
		return com.stocker.pricing.model.PriceRecord.builder()
				.itemId(record.getItemId())
				.storeId(record.getStoreId())
				.chainId(record.getChainId())
				.channel(record.getChannel())
				.priceAmount(java.math.BigDecimal.valueOf(record.getPriceAmount()))
				.currency(record.getCurrency())
				.promoFlag(record.getPromoFlag())
				.capturedAt(toOffsetDateTime(record.getCapturedAt()))
				.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
				.build();
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
