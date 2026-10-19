package com.stocker.pricing.acceptance;

import com.stocker.pricing.api.grpc.PriceRecordGrpcController;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.ShoppingListItem;
import com.stocker.pricing.api.grpc.v1.StoreTotal;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.ItemPriceComparisonService;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.acceptance.PriceFixtures.weeklyShop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Both stories at the contract the gateway calls: the real gRPC controller, comparison and savings services
 * with only the price table faked. Proves what the user is shown, not how it is computed.
 */
@Tag("feature-integration")
class CompareContractAcceptanceTest {

	private PriceRecordGrpcController controller;

	private static final class Capture implements StreamObserver<CompareShoppingListResponse> {
		CompareShoppingListResponse response;
		Throwable error;

		@Override
		public void onNext(CompareShoppingListResponse value) {
			response = value;
		}

		@Override
		public void onError(Throwable t) {
			error = t;
		}

		@Override
		public void onCompleted() {
		}
	}

	@BeforeEach
	void setUp() {
		PriceStatsRepository prices = mock(PriceStatsRepository.class);
		when(prices.findByItemIdIn(anyCollection())).thenReturn(weeklyShop());
		ServiceAreaProperties area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland"));
		controller = new PriceRecordGrpcController(mock(PriceFetcherService.class), mock(PriceSearchService.class),
				new ShoppingListComparisonService(prices, area), new SavingsCalculatorService(),
				mock(ItemPriceComparisonService.class), mock(ItemMatchService.class));
	}

	private static CompareShoppingListRequest.Builder weeklyList(String region) {
		return CompareShoppingListRequest.newBuilder().setRegion(region)
				.addItems(ShoppingListItem.newBuilder().setItemId("milk").setQuantity(2))
				.addItems(ShoppingListItem.newBuilder().setItemId("eggs").setQuantity(1))
				.addItems(ShoppingListItem.newBuilder().setItemId("butter").setQuantity(1));
	}

	private Capture call(CompareShoppingListRequest request) {
		Capture capture = new Capture();
		controller.compareShoppingList(request, capture);
		return capture;
	}

	@Test
	void theUserIsShownEverySupermarketsTotalCheapestFirstWithTheRecommendation() {
		CompareShoppingListResponse response = call(weeklyList("auckland").build()).response;

		assertThat(response.getCheapestChainId()).isEqualTo("paknsave");
		List<String> order = new ArrayList<>();
		response.getStoreTotalsList().forEach(total -> order.add(total.getChainId() + "=" + total.getTotalAmount()));
		assertThat(order).containsExactly("paknsave=17.0", "woolworths=17.7", "newworld=19.0");
	}

	@Test
	void savingsAreRelativeToTheOptionTheUserSelected() {
		CompareShoppingListResponse response = call(weeklyList("auckland").setSelectedChainId("newworld").build()).response;

		assertThat(response.getEffectiveSelectedChainId()).isEqualTo("newworld");
		assertThat(savingFor(response, "newworld")).isEqualTo(0.0);
		assertThat(savingFor(response, "paknsave")).isEqualTo(2.0);
		assertThat(savingFor(response, "woolworths")).isEqualTo(2.1);   // 1.30 cheaper + 0.80 on special
	}

	@Test
	void withoutASelectionTheRecommendedStoreIsTheBaseline() {
		CompareShoppingListResponse response = call(weeklyList("auckland").build()).response;

		assertThat(response.getEffectiveSelectedChainId()).isEqualTo("paknsave");
		assertThat(savingFor(response, "paknsave")).isEqualTo(0.0);
		assertThat(savingFor(response, "newworld")).isEqualTo(-2.0);
	}

	@Test
	void outsideTheServiceAreaTheUserIsToldWhy() {
		Capture capture = call(weeklyList("queenstown").build());

		assertThat(capture.response).isNull();
		assertOutsideServiceArea(capture.error);
	}

	@Test
	void anEmptyListIsAlsoReportedAsOutsideTheServiceArea() {
		assertOutsideServiceArea(call(CompareShoppingListRequest.newBuilder().setRegion("auckland").build()).error);
	}

	private static double savingFor(CompareShoppingListResponse response, String chain) {
		return response.getStoreTotalsList().stream().filter(t -> t.getChainId().equals(chain)).map(StoreTotal::getSavingsAmount)
				.findFirst().orElseThrow();
	}

	private static void assertOutsideServiceArea(Throwable error) {
		assertThat(error).isInstanceOf(StatusRuntimeException.class);
		StatusRuntimeException status = (StatusRuntimeException) error;
		assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		assertThat(status.getStatus().getDescription()).isEqualTo(ServiceAreaException.OUTSIDE_SERVICE_AREA_MESSAGE);
	}
}
