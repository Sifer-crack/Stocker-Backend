package com.stocker.pricing.acceptance;

import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.service.fulfilment.FulfilmentMethod;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.acceptance.PriceFixtures.weeklyShop;
import static com.stocker.pricing.service.FulfilmentFixtures.fees;
import static com.stocker.pricing.service.FulfilmentFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Trello criteria: given known travel / delivery / click-and-collect costs, the true cheapest option includes them.
 * Items total for "2 milk, 1 eggs, 1 butter": paknsave 17.00, woolworths 17.70, newworld 19.00 (see PriceFixtures).
 */
@Tag("service")
class FulfilmentScenarioTest {

	private static final List<RequestedItem> WEEKLY_LIST =
			List.of(new RequestedItem("milk", 2), new RequestedItem("eggs", 1), new RequestedItem("butter", 1));

	private PriceStatsRepository prices;
	private ServiceAreaProperties area;

	@BeforeEach
	void setUp() {
		prices = mock(PriceStatsRepository.class);
		when(prices.findByItemIdIn(anyCollection())).thenReturn(weeklyShop());
		area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland"));
	}

	private ComparisonResult compare(FulfilmentProperties fees, Set<FulfilmentMethod> allowed) {
		return new ShoppingListComparisonService(prices, area, fees).compare(WEEKLY_LIST, "auckland", allowed);
	}

	private static String totals(ComparisonResult result) {
		return result.chainTotals().stream().map(c -> c.chainId() + "=" + c.totalWithFulfilment().toPlainString())
				.reduce((a, b) -> a + " " + b).orElse("");
	}

	@Test
	void travelCostMakesTheCheapestShelfPriceNotTheCheapestTrip() {
		var fees = properties(Map.of("paknsave", fees("4.00", null, null, null), "woolworths", fees("0.50", null, null, null),
				"newworld", fees("0.00", null, null, null)));

		ComparisonResult result = compare(fees, Set.of(FulfilmentMethod.IN_STORE));

		assertThat(totals(result)).isEqualTo("woolworths=18.20 newworld=19.00 paknsave=21.00");
		assertThat(result.cheapestChainId()).isEqualTo("woolworths");
	}

	@Test
	void deliveryFeesIncludingAFreeDeliveryThresholdPickTheCheapestDelivery() {
		var fees = properties(Map.of("paknsave", fees("0", "9.00", null, null), "woolworths", fees("0", "6.00", null, "20.00"),
				"newworld", fees("0", "5.00", null, null)));

		ComparisonResult result = compare(fees, Set.of(FulfilmentMethod.DELIVERY));

		assertThat(totals(result)).isEqualTo("woolworths=23.70 newworld=24.00 paknsave=26.00");
		assertThat(result.cheapestChainId()).isEqualTo("woolworths");
	}

	@Test
	void clickAndCollectFeesCanFlipTheWinnerToTheDearerShelf() {
		var fees = properties(Map.of("paknsave", fees("0", null, "3.00", null), "woolworths", fees("0", null, "4.50", null),
				"newworld", fees("0", null, "0.00", null)));

		ComparisonResult result = compare(fees, Set.of(FulfilmentMethod.CLICK_AND_COLLECT));

		assertThat(totals(result)).isEqualTo("newworld=19.00 paknsave=20.00 woolworths=22.20");
		assertThat(result.cheapestChainId()).isEqualTo("newworld");
	}

	@Test
	void mixedMethodsPickEachChainsBestWayAndTheOverallCheapest() {
		ComparisonResult result = compare(com.stocker.pricing.service.FulfilmentFixtures.mixedReference(), Set.of());

		assertThat(totals(result)).isEqualTo("woolworths=18.20 newworld=19.00 paknsave=20.00");
		assertThat(result.cheapestChainId()).isEqualTo("woolworths");
		assertThat(best(result, "paknsave")).isEqualTo(FulfilmentMethod.CLICK_AND_COLLECT);
		assertThat(best(result, "woolworths")).isEqualTo(FulfilmentMethod.IN_STORE);
		assertThat(best(result, "newworld")).as("19.00 tie in_store vs click_and_collect").isEqualTo(FulfilmentMethod.IN_STORE);

		List<ChainSavings> savings = new SavingsCalculatorService().calculate(result.chainTotals(), "paknsave");
		// savings relative to paknsave's 20.00 (woolworths has a 0.80 special on butter)
		assertThat(savings).extracting(ChainSavings::chainId).containsExactly("woolworths", "newworld", "paknsave");
		assertThat(savings.get(0).savingsAmount()).isEqualByComparingTo("2.60");   // 20.00-18.20 + 0.80
		assertThat(savings.get(1).savingsAmount()).isEqualByComparingTo("1.00");
	}

	private static FulfilmentMethod best(ComparisonResult result, String chain) {
		ChainTotal total = result.chainTotals().stream().filter(c -> c.chainId().equals(chain)).findFirst().orElseThrow();
		return total.bestOption().method();
	}
}
