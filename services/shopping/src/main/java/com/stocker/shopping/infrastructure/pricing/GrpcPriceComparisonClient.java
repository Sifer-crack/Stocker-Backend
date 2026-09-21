package com.stocker.shopping.infrastructure.pricing;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.CompareItemPricesRequest;
import com.stocker.pricing.api.grpc.v1.CompareItemPricesResponse;
import com.stocker.pricing.api.grpc.v1.ComparisonItem;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.StorePrice;
import com.stocker.shopping.application.model.PricePoint;
import com.stocker.shopping.application.port.PriceComparisonClient;
import com.stocker.shopping.application.port.PricingRejectedException;
import com.stocker.shopping.application.port.PricingUnavailableException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Calls pricing's {@code CompareItemPrices} RPC exactly once, under a deadline. No retry, by
 * design: a failed or slow call leaves the item pending and the Kafka backfill takes over.
 */
@Component
public class GrpcPriceComparisonClient implements PriceComparisonClient {

	private final PriceRecordServiceGrpc.PriceRecordServiceBlockingStub stub;
	private final Duration timeout;

	public GrpcPriceComparisonClient(PriceRecordServiceGrpc.PriceRecordServiceBlockingStub stub,
			@Value("${app.pricing.compare-timeout:PT8S}") Duration timeout) {
		this.stub = stub;
		this.timeout = timeout;
	}

	@Override
	public List<PricePoint> compare(String itemId, String searchTerm, String category, String region) {
		CompareItemPricesRequest request = CompareItemPricesRequest.newBuilder()
				.setRegion(region == null ? "" : region)
				.addItems(ComparisonItem.newBuilder()
						.setItemId(itemId)
						.setSearchTerm(searchTerm)
						.setCategory(category == null ? "" : category))
				.build();
		try {
			CompareItemPricesResponse response =
					stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).compareItemPrices(request);
			return response.getComparisonsList().stream()
					.filter(comparison -> comparison.getItemId().equals(itemId))
					.findFirst()
					.map(comparison -> comparison.getPricesList().stream().map(GrpcPriceComparisonClient::toPoint).toList())
					.orElse(List.of());
		} catch (StatusRuntimeException e) {
			if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT) {
				throw new PricingRejectedException(e.getStatus().getDescription());
			}
			throw new PricingUnavailableException(e.getStatus().getCode().name(), e);
		}
	}

	private static PricePoint toPoint(StorePrice price) {
		return new PricePoint(price.getChainId(), price.getStoreId(),
				BigDecimal.valueOf(price.getPriceAmount()).setScale(2, RoundingMode.HALF_UP),
				price.getCurrency(), price.getPromoFlag(), toOffsetDateTime(price.getCapturedAt()));
	}

	/** An unset proto timestamp is the epoch; treat it as "unknown" rather than 1970. */
	private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
		if (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0) {
			return null;
		}
		return OffsetDateTime.ofInstant(Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()), ZoneOffset.UTC);
	}
}
