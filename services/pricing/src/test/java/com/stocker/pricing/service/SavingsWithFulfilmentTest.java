package com.stocker.pricing.service;

import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.FulfilmentOption;
import com.stocker.pricing.service.fulfilment.FulfilmentMethod;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SavingsWithFulfilmentTest {

	private static ChainTotal chain(String id, String items, String fee, String discount) {
		BigDecimal total = new BigDecimal(items);
		BigDecimal withFee = total.add(new BigDecimal(fee));
		FulfilmentOption best = new FulfilmentOption(FulfilmentMethod.DELIVERY, new BigDecimal(fee), withFee);
		return new ChainTotal(id, total, "NZD", 1, 1, List.of(), new BigDecimal(discount), List.of(best), best, withFee);
	}

	private static BigDecimal savingFor(List<ChainSavings> savings, String id) {
		return savings.stream().filter(s -> s.chainId().equals(id)).map(ChainSavings::savingsAmount).findFirst().orElseThrow();
	}

	@Test
	void savingsUseTheTotalIncludingFulfilmentFees() {
		// selected: 10 + 5 fee = 15. other: 12 + 0 fee = 12 -> saves 3 although its items cost more.
		List<ChainSavings> savings = new SavingsCalculatorService()
				.calculate(List.of(chain("selected", "10.00", "5.00", "0.00"), chain("other", "12.00", "0.00", "0.00")), "selected");

		assertThat(savingFor(savings, "selected")).isEqualByComparingTo("0.00");
		assertThat(savingFor(savings, "other")).isEqualByComparingTo("3.00");
	}

	@Test
	void discountIsStillAddedOnTopOfTheFeeAdjustedDifference() {
		List<ChainSavings> savings = new SavingsCalculatorService()
				.calculate(List.of(chain("selected", "10.00", "0.00", "0.00"), chain("other", "10.00", "2.00", "1.50")), "selected");

		assertThat(savingFor(savings, "other")).isEqualByComparingTo("-0.50");   // -2.00 fee + 1.50 discount
	}

	@Test
	void savingsCoverOnlyTheChainsThatWereLeftInTheResults() {
		// the excluded chain is simply not in the list handed to the calculator
		List<ChainSavings> savings = new SavingsCalculatorService()
				.calculate(List.of(chain("woolworths", "17.70", "14.00", "0.00"), chain("newworld", "19.00", "14.00", "0.00")),
						"woolworths");

		assertThat(savings).extracting(ChainSavings::chainId).containsExactly("woolworths", "newworld");
		assertThat(savingFor(savings, "newworld")).isEqualByComparingTo("-1.30");
	}

	@Test
	void whenTheSelectedChainIsNotInTheResultsTheBaselineIsZero() {
		// Existing behaviour, relied on when the selected chain was excluded: baseline 0, so savings = discount - total.
		List<ChainSavings> savings = new SavingsCalculatorService()
				.calculate(List.of(chain("other", "12.00", "3.00", "1.00")), "gone");

		assertThat(savingFor(savings, "other")).isEqualByComparingTo("-14.00");   // 0 - 15.00 + 1.00
	}

	@Test
	void anEmptyResultYieldsNoSavings() {
		assertThat(new SavingsCalculatorService().calculate(List.of(), "any")).isEmpty();
	}
}
