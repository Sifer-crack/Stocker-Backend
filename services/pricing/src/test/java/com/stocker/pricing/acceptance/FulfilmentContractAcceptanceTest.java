package com.stocker.pricing.acceptance;

import com.stocker.pricing.api.grpc.PriceRecordGrpcController;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.FulfilmentOption;
import com.stocker.pricing.api.grpc.v1.ShoppingListItem;
import com.stocker.pricing.api.grpc.v1.StoreTotal;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.ItemPriceComparisonService;
import com.stocker.pricing.service.PriceFetcherService;
import com.stocker.pricing.service.PriceSearchService;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.match.ItemMatchService;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.acceptance.PriceFixtures.weeklyShop;
import static com.stocker.pricing.service.FulfilmentFixtures.mixedReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the gateway receives when the user's known delivery / click-and-collect costs are in play (travel is out of scope):
 * real gRPC controller, comparison and savings services; only the price table is faked.
 */
@Tag("feature-integration")
class FulfilmentContractAcceptanceTest {

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
				new ShoppingListComparisonService(prices, area, mixedReference()), new SavingsCalculatorService(),
				mock(ItemPriceComparisonService.class), mock(ItemMatchService.class));
	}

	private Capture call(String... allowedMethods) {
		return callSelecting("", allowedMethods);
	}

	private Capture callSelecting(String selectedChainId, String... allowedMethods) {
		CompareShoppingListRequest request = CompareShoppingListRequest.newBuilder().setRegion("auckland")
				.setSelectedChainId(selectedChainId)
				.addItems(ShoppingListItem.newBuilder().setItemId("milk").setQuantity(2))
				.addItems(ShoppingListItem.newBuilder().setItemId("eggs").setQuantity(1))
				.addItems(ShoppingListItem.newBuilder().setItemId("butter").setQuantity(1))
				.addAllAllowedMethods(List.of(allowedMethods)).build();
		Capture capture = new Capture();
		controller.compareShoppingList(request, capture);
		return capture;
	}

	private static StoreTotal chain(CompareShoppingListResponse response, String id) {
		return response.getStoreTotalsList().stream().filter(t -> t.getChainId().equals(id)).findFirst().orElseThrow();
	}

	@Test
	void withAllMethodsTheTrueCheapestIncludesFeesAndExplainsEachChainsBestWay() {
		CompareShoppingListResponse response = call().response;

		assertThat(response.getCheapestChainId()).isEqualTo("woolworths");
		assertThat(response.getStoreTotalsList()).extracting(StoreTotal::getChainId)
				.containsExactly("woolworths", "paknsave", "newworld");

		StoreTotal woolworths = chain(response, "woolworths");
		assertThat(woolworths.getTotalAmount()).isEqualTo(17.7);              // items only
		assertThat(woolworths.getTotalWithFulfilment()).isEqualTo(21.2);
		assertThat(woolworths.getBestMethod()).isEqualTo("click_and_collect");
		assertThat(woolworths.getFulfilmentCost()).isEqualTo(3.5);
		assertThat(woolworths.getFulfilmentOptionsList()).extracting(FulfilmentOption::getMethod)
				.containsExactly("click_and_collect", "delivery");
		assertThat(woolworths.getFulfilmentOptionsList()).extracting(FulfilmentOption::getTotalAmount)
				.containsExactly(21.2, 31.7);

		StoreTotal paknsave = chain(response, "paknsave");
		assertThat(paknsave.getBestMethod()).isEqualTo("click_and_collect");
		assertThat(paknsave.getTotalWithFulfilment()).isEqualTo(22.0);
		assertThat(paknsave.getFulfilmentOptionsList()).extracting(FulfilmentOption::getMethod)
				.containsExactly("click_and_collect");
		assertThat(chain(response, "newworld").getBestMethod()).isEqualTo("click_and_collect");
	}

	@Test
	void savingsAreRelativeToTheRecommendedOptionsTotalWithFees() {
		CompareShoppingListResponse response = call().response;

		assertThat(response.getEffectiveSelectedChainId()).isEqualTo("woolworths");
		assertThat(chain(response, "woolworths").getSavingsAmount()).isEqualTo(0.8);   // own discount only
		assertThat(chain(response, "paknsave").getSavingsAmount()).isEqualTo(-0.8);    // 21.20 - 22.00
		assertThat(chain(response, "newworld").getSavingsAmount()).isEqualTo(-2.8);    // 21.20 - 24.00
	}

	@Test
	void restrictingToDeliveryLeavesPaknsaveOutOfTheResponse() {
		CompareShoppingListResponse response = call("delivery").response;

		assertThat(response.getCheapestChainId()).isEqualTo("woolworths");
		assertThat(response.getStoreTotalsList()).extracting(StoreTotal::getChainId)
				.containsExactly("woolworths", "newworld");
		assertThat(chain(response, "woolworths").getTotalWithFulfilment()).isEqualTo(31.7);
		assertThat(chain(response, "newworld").getTotalWithFulfilment()).isEqualTo(33.0);
		// no misleading 14.70 saving: savings only cover the chains that remain
		assertThat(response.getStoreTotalsList()).extracting(StoreTotal::getSavingsAmount)
				.allSatisfy(saving -> assertThat(saving).isLessThan(5.0));
	}

	@Test
	void whenTheSelectedChainWasExcludedTheBaselineFallsBackToZero() {
		CompareShoppingListResponse response = callSelecting("paknsave", "delivery").response;

		assertThat(response.getEffectiveSelectedChainId()).isEqualTo("paknsave");
		assertThat(response.getStoreTotalsList()).extracting(StoreTotal::getChainId)
				.containsExactly("woolworths", "newworld");
		// not in the results, so the baseline is 0 (as for any unknown selected chain): savings = discount - total
		assertThat(chain(response, "woolworths").getSavingsAmount()).isEqualTo(-30.9);   // 0 - 31.70 + 0.80
		assertThat(chain(response, "newworld").getSavingsAmount()).isEqualTo(-33.0);
	}

	@Test
	void clickAndCollectOnlyKeepsEveryChain() {
		CompareShoppingListResponse response = call("click_and_collect").response;

		assertThat(response.getCheapestChainId()).isEqualTo("woolworths");
		assertThat(chain(response, "newworld").getTotalWithFulfilment()).isEqualTo(24.0);
		assertThat(response.getStoreTotalsList()).allSatisfy(t -> assertThat(t.getBestMethod()).isEqualTo("click_and_collect"));
	}

	@Test
	void inStoreIsNoLongerAValidMethod() {
		Capture capture = call("in_store");

		assertThat(capture.response).isNull();
		StatusRuntimeException status = (StatusRuntimeException) capture.error;
		assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		assertThat(status.getStatus().getDescription()).contains("in_store", "click_and_collect, delivery");
	}

	@Test
	void anUnknownMethodNameIsRejectedAndTheMessageListsTheValidNames() {
		Capture capture = call("drone");

		assertThat(capture.response).isNull();
		StatusRuntimeException status = (StatusRuntimeException) capture.error;
		assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
		assertThat(status.getStatus().getDescription()).contains("drone", "click_and_collect, delivery");
	}
}
