package com.stocker.pricing.acceptance;

import com.stocker.pricing.service.SavingsCalculatorService;
import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.acceptance.PriceFixtures.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Story: "show how much money I would save with each option so I can make an informed decision". */
class SavingsAcceptanceTest {

	private final SavingsCalculatorService calculator = new SavingsCalculatorService();

	private static ChainTotal total(String chain, String amount, String discount) {
		return new ChainTotal(chain, new BigDecimal(amount), "NZD", 3, 3, List.of(), new BigDecimal(discount));
	}

	private static BigDecimal savingOf(List<ChainSavings> savings, String chain) {
		return savings.stream().filter(s -> s.chainId().equals(chain)).findFirst().orElseThrow().savingsAmount();
	}

	// AC1: Given a fixed list of selected options and alternatives, when the system calculates the savings, then
	// Stocker correctly calculates and displays the savings amount for each option relative to the selected option.
	@Test
	void savingsAreCalculatedForEachOptionRelativeToTheSelectedOne() {
		List<ChainTotal> options = List.of(total("paknsave", "17.00", "0"), total("woolworths", "17.70", "0"),
				total("newworld", "19.00", "0"));

		List<ChainSavings> savings = calculator.calculate(options, "woolworths");

		assertThat(savingOf(savings, "woolworths")).isEqualByComparingTo("0.00");   // the selected option saves nothing
		assertThat(savingOf(savings, "paknsave")).isEqualByComparingTo("0.70");     // 17.70 - 17.00
		assertThat(savingOf(savings, "newworld")).isEqualByComparingTo("-1.30");    // costs 1.30 more: shown, not hidden
	}

	@Test
	void aDiscountAddsToTheSavingOfTheOptionThatHasIt() {
		List<ChainTotal> options = List.of(total("paknsave", "17.00", "0"), total("woolworths", "17.70", "0.80"));

		List<ChainSavings> savings = calculator.calculate(options, "paknsave");

		assertThat(savingOf(savings, "woolworths")).isEqualByComparingTo("0.10");   // (17.00 - 17.70) + 0.80
	}

	// AC2: Given a fixed list of options where all have the same price, when the system calculates the savings,
	// then Stocker only displays savings resulting from discounts.
	@Test
	void whenEveryOptionCostsTheSameOnlyDiscountsShowAsSavings() {
		List<ChainTotal> options = List.of(total("paknsave", "20.00", "0"), total("woolworths", "20.00", "1.50"),
				total("newworld", "20.00", "0.25"));

		List<ChainSavings> savings = calculator.calculate(options, "paknsave");

		assertThat(savingOf(savings, "paknsave")).isEqualByComparingTo("0.00");
		assertThat(savingOf(savings, "woolworths")).isEqualByComparingTo("1.50");
		assertThat(savingOf(savings, "newworld")).isEqualByComparingTo("0.25");
	}

	// AC3: Given an empty list of options, when the system attempts to find suitable stores, then Stocker displays
	// an error message informing the user that they are outside Stocker's service area.
	@Test
	void anEmptyListOfOptionsIsReportedAsOutsideTheServiceArea() {
		ShoppingListComparisonService comparison = new ShoppingListComparisonService(mock(PriceStatsRepository.class),
				supportedAuckland());

		assertThatThrownBy(() -> comparison.compare(List.of(), "auckland"))
				.isInstanceOf(ServiceAreaException.class)
				.hasMessageContaining("outside Stocker's service area");
		assertThat(calculator.calculate(List.of(), "paknsave")).isEmpty();
	}

	@Test
	void savingsFromARealComparisonUseTheSpecialPriceDiscount() {
		PriceStatsRepository prices = mock(PriceStatsRepository.class);
		// both chains charge 10.00 for the one item, but woolworths has it on special (was 12.00)
		when(prices.findByItemIdIn(anyCollection())).thenReturn(List.of(
				price("paknsave", "milk", "10.00"), price("woolworths", "milk", "10.00", "12.00", true)));
		ComparisonResult result = new ShoppingListComparisonService(prices, supportedAuckland())
				.compare(List.of(new RequestedItem("milk", 1)), "auckland");

		List<ChainSavings> savings = calculator.calculate(result.chainTotals(), "paknsave");

		assertThat(savingOf(savings, "paknsave")).isEqualByComparingTo("0.00");
		assertThat(savingOf(savings, "woolworths")).isEqualByComparingTo("2.00");
	}

	private static ServiceAreaProperties supportedAuckland() {
		ServiceAreaProperties area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland"));
		return area;
	}
}
