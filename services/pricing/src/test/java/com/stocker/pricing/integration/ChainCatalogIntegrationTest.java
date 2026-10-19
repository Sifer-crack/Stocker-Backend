package com.stocker.pricing.integration;

import com.stocker.pricing.api.grpc.ChainCatalogGrpcController;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.ChainProductService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pricing half of the favorite-stores story: the gRPC call the gateway makes, through the real controller and
 * service, reading stored (scraped) rows. Database only: it can never make a supermarket request.
 */
@Tag("feature-integration")
class ChainCatalogIntegrationTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);

	private ChainCatalogGrpcController controller;

	@BeforeEach
	void setUp() {
		PriceRecordRepository repository = mock(PriceRecordRepository.class);
		when(repository.findIngestedByChainSince(eq("paknsave"), any())).thenReturn(List.of(
				PriceRecord.builder().itemId("paknsave:1").chainId("paknsave").storeId("paknsave:").channel("pickup")
						.priceAmount(new BigDecimal("4.80")).currency("NZD").promoFlag(true).capturedAt(NOW)
						.createdAt(NOW).rawAttributes(Map.of("name", "Anchor Blue Top Milk", "brand", "2l")).build()));
		controller = new ChainCatalogGrpcController(new ChainProductService(repository, new PricingCacheProperties()));
	}

	private static final class Capture<T> implements StreamObserver<T> {
		final List<T> values = new ArrayList<>();
		Throwable error;
		boolean completed;

		@Override
		public void onNext(T value) {
			values.add(value);
		}

		@Override
		public void onError(Throwable t) {
			error = t;
		}

		@Override
		public void onCompleted() {
			completed = true;
		}
	}

	@Test
	void aFavoriteStoresProductsAreServedFromStoredPrices() {
		Capture<ListChainProductsResponse> out = new Capture<>();

		controller.listProducts(ListChainProductsRequest.newBuilder().setChainId("PAKNSAVE").setQuery("milk").build(), out);

		assertThat(out.completed).isTrue();
		ListChainProductsResponse response = out.values.get(0);
		assertThat(response.getProductsCount()).isEqualTo(1);
		assertThat(response.getProducts(0).getName()).isEqualTo("Anchor Blue Top Milk 2l");
		assertThat(response.getProducts(0).getPriceAmount()).isEqualTo(4.80);
		assertThat(response.getProducts(0).getPromoFlag()).isTrue();
		assertThat(response.getProducts(0).getItemId()).isEqualTo("paknsave:1");
	}

	@Test
	void anUnknownStoreIsRejectedAsInvalidArgument() {
		Capture<ListChainProductsResponse> out = new Capture<>();

		controller.listProducts(ListChainProductsRequest.newBuilder().setChainId("countdown").build(), out);

		assertThat(out.error).isInstanceOf(StatusRuntimeException.class);
		assertThat(((StatusRuntimeException) out.error).getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
	}
}
