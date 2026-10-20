package com.stocker.pricing.acceptance;

import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.acceptance.PriceFixtures.price;
import static com.stocker.pricing.acceptance.PriceFixtures.weeklyShop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How a household uses the comparison: compare, accept the recommendation, then look at the savings. */
@Tag("service")
class ShoppingListScenarioTest {

	private PriceStatsRepository prices;
	private ShoppingListComparisonService comparison;
	private final SavingsCalculatorService savings = new SavingsCalculatorService();

	@BeforeEach
	void setUp() {
		prices = mock(PriceStatsRepository.class);
		when(prices.findByItemIdIn(anyCollection())).thenReturn(weeklyShop());
		ServiceAreaProperties area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland", "wellington", "christchurch"));
		comparison = new ShoppingListComparisonService(prices, area);
	}

	private static final List<RequestedItem> WEEKLY_LIST = List.of(
			new RequestedItem("milk", 2), new RequestedItem("eggs", 1), new RequestedItem("butter", 1));

	@Test
	void theRecommendedStoreIsTheOneWithTheLowestFullTotalAndTheSavingsExplainIt() {
		ComparisonResult result = comparison.compare(WEEKLY_LIST, "wellington");
		String recommended = result.cheapestChainId();

		// the household was going to shop at New World (19.00): what would each option save them?
		List<ChainSavings> versusNewWorld = savings.calculate(result.chainTotals(), "newworld");

		assertThat(recommended).isEqualTo("paknsave");
		assertThat(versusNewWorld).filteredOn(s -> s.chainId().equals("paknsave")).singleElement()
				.satisfies(s -> assertThat(s.savingsAmount()).isEqualByComparingTo("2.00"));    // 19.00 - 17.00
		assertThat(versusNewWorld).filteredOn(s -> s.chainId().equals("woolworths")).singleElement()
				.satisfies(s -> assertThat(s.savingsAmount()).isEqualByComparingTo("2.10"));    // 1.30 + 0.80 special
	}

	@Test
	void whenNoSupermarketCarriesEverythingTheBestPartialTotalIsStillRecommended() {
		when(prices.findByItemIdIn(anyCollection())).thenReturn(List.of(
				price("paknsave", "milk", "3.00"), price("newworld", "milk", "3.50"), price("newworld", "eggs", "6.50")));

		ComparisonResult result = comparison.compare(WEEKLY_LIST, "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("paknsave");
		assertThat(result.chainTotals()).allSatisfy(total -> assertThat(total.hasFullCoverage()).isFalse());
	}

	@Test
	void aUserOutsideTheAreaIsToldSoWhateverTheirList() {
		assertThatThrownBy(() -> comparison.compare(WEEKLY_LIST, "dunedin")).isInstanceOf(ServiceAreaException.class)
				.hasMessage(ServiceAreaException.OUTSIDE_SERVICE_AREA_MESSAGE);
		assertThatThrownBy(() -> comparison.compare(List.of(), "auckland")).isInstanceOf(ServiceAreaException.class)
				.hasMessage(ServiceAreaException.OUTSIDE_SERVICE_AREA_MESSAGE);
	}
}
