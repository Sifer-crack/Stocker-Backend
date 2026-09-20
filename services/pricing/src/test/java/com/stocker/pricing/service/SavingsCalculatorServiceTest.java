package com.stocker.pricing.service;

import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SavingsCalculatorServiceTest {

	private final SavingsCalculatorService service = new SavingsCalculatorService();

	@Test
	void calculatesSavingsRelativeToSelectedOptionIncludingANegativeCase() {
		List<ChainTotal> chainTotals = List.of(
				chainTotal("selected", "5.00"),
				chainTotal("cheaper", "3.00"),
				chainTotal("pricier", "6.00"));

		List<ChainSavings> savings = service.calculate(chainTotals, "selected");

		assertEquals(new BigDecimal("0.00"), savingsFor(savings, "selected"));
		assertEquals(new BigDecimal("2.00"), savingsFor(savings, "cheaper"), "5.00 - 3.00");
		assertEquals(new BigDecimal("-1.00"), savingsFor(savings, "pricier"), "costs more than the selected option");
	}

	@Test
	void whenAllOptionsHaveTheSameTotalOnlyDiscountSavingsAreDisplayed() {
		List<ChainTotal> chainTotals = List.of(
				chainTotal("selected", "5.00"),
				new ChainTotal("discounted", new BigDecimal("5.00"), "NZD", 1, 1, List.of(), new BigDecimal("1.50")),
				chainTotal("plain", "5.00"));

		List<ChainSavings> savings = service.calculate(chainTotals, "selected");

		assertEquals(new BigDecimal("0.00"), savingsFor(savings, "selected"));
		assertEquals(new BigDecimal("1.50"), savingsFor(savings, "discounted"), "tied total, but a real discount");
		assertEquals(new BigDecimal("0.00"), savingsFor(savings, "plain"), "tied total, no discount");
	}

	@Test
	void fallsBackToZeroBaselineWhenSelectedChainIdIsNotInTheList() {
		List<ChainTotal> chainTotals = List.of(chainTotal("onlyOption", "4.00"));

		List<ChainSavings> savings = service.calculate(chainTotals, "does-not-exist");

		assertEquals(new BigDecimal("-4.00"), savingsFor(savings, "onlyOption"));
	}

	private static BigDecimal savingsFor(List<ChainSavings> savings, String chainId) {
		return savings.stream()
				.filter(chainSavings -> chainSavings.chainId().equals(chainId))
				.findFirst()
				.orElseThrow()
				.savingsAmount();
	}

	private static ChainTotal chainTotal(String chainId, String totalAmount) {
		return new ChainTotal(chainId, new BigDecimal(totalAmount), "NZD", 1, 1, List.of(), BigDecimal.ZERO);
	}
}
