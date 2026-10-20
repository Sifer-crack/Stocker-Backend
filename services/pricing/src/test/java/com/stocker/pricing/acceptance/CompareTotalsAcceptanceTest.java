package com.stocker.pricing.acceptance;

import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.acceptance.PriceFixtures.price;
import static com.stocker.pricing.acceptance.PriceFixtures.weeklyShop;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story: "compare the total price of my shopping list across supermarkets so it can recommend the cheapest one".
 * Each test name is the acceptance criterion it proves.
 */
class CompareTotalsAcceptanceTest {

	private static final List<RequestedItem> LIST = List.of(
			new RequestedItem("milk", 2), new RequestedItem("eggs", 1), new RequestedItem("butter", 1));

	private PriceStatsRepository prices;
	private ShoppingListComparisonService service;

	@BeforeEach
	void setUp() {
		prices = mock(PriceStatsRepository.class);
		when(prices.findByItemIdIn(anyCollection())).thenReturn(weeklyShop());
		ServiceAreaProperties area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland", "wellington"));
		service = new ShoppingListComparisonService(prices, area);
	}

	private static ChainTotal totalOf(ComparisonResult result, String chain) {
		return result.chainTotals().stream().filter(total -> total.chainId().equals(chain)).findFirst().orElseThrow();
	}

	// AC1: Given a known list of item totals at various supermarkets, when the system compares the available
	// options, then Stocker selects the option with the correct total cost.
	@Test
	void knownTotalsAreComparedAndTheCheapestOptionIsSelected() {
		ComparisonResult result = service.compare(LIST, "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("paknsave");
		assertThat(result.chainTotals()).extracting(ChainTotal::chainId)
				.containsExactly("paknsave", "woolworths", "newworld");
	}

	// AC1 (the trap): a supermarket that is missing an item must not win just because its partial total is lower.
	@Test
	void aSupermarketMissingAnItemDoesNotWinOnAPartialTotal() {
		when(prices.findByItemIdIn(anyCollection())).thenReturn(List.of(
				price("cheapo", "milk", "1.00"),                       // only milk: partial total 2.00
				price("newworld", "milk", "3.50"), price("newworld", "eggs", "6.50"), price("newworld", "butter", "5.50")));

		ComparisonResult result = service.compare(LIST, "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("newworld");
		assertThat(totalOf(result, "cheapo").itemsPriced()).isEqualTo(1);
		assertThat(totalOf(result, "cheapo").unavailableItemIds()).containsExactlyInAnyOrder("eggs", "butter");
	}

	// AC2: Given a shopping list has been generated, when the system calculates the total cost at each
	// supermarket, then Stocker accurately calculates and compares the total cost for each supermarket.
	@Test
	void everySupermarketsTotalIsTheSumOfPriceTimesQuantity() {
		ComparisonResult result = service.compare(LIST, "auckland");

		assertThat(totalOf(result, "paknsave").totalAmount()).isEqualByComparingTo("17.00");    // 2x3.00 + 6.00 + 5.00
		assertThat(totalOf(result, "woolworths").totalAmount()).isEqualByComparingTo("17.70");  // 2x3.20 + 6.10 + 5.20
		assertThat(totalOf(result, "newworld").totalAmount()).isEqualByComparingTo("19.00");    // 2x3.50 + 6.50 + 5.50
		assertThat(result.chainTotals()).allSatisfy(total -> {
			assertThat(total.itemsPriced()).isEqualTo(3);
			assertThat(total.currency()).isEqualTo("NZD");
		});
	}

	@Test
	void theSameItemListedTwiceIsCountedOnce() {
		ComparisonResult result = service.compare(List.of(new RequestedItem("milk", 1), new RequestedItem("milk", 1),
				new RequestedItem("eggs", 1), new RequestedItem("butter", 1)), "auckland");

		assertThat(totalOf(result, "paknsave").totalAmount()).isEqualByComparingTo("17.00");
	}

	// AC3: Given the user is outside Stocker's service area, when Stocker searches for supermarkets within the
	// specified area, then Stocker displays an error message explaining the user is outside the service area.
	@Test
	void aUserOutsideTheServiceAreaGetsAnExplanatoryError() {
		assertThatThrownBy(() -> service.compare(LIST, "queenstown"))
				.isInstanceOf(ServiceAreaException.class)
				.hasMessageContaining("outside Stocker's service area");
	}

	@Test
	void theRegionCheckIgnoresCaseAndSpaces() {
		assertThat(service.compare(LIST, "  AUCKLAND ").cheapestChainId()).isEqualTo("paknsave");
	}

	@Test
	void aMissingRegionIsOutsideTheServiceArea() {
		assertThatThrownBy(() -> service.compare(LIST, null)).isInstanceOf(ServiceAreaException.class);
		assertThatThrownBy(() -> service.compare(LIST, " ")).isInstanceOf(ServiceAreaException.class);
	}
}
