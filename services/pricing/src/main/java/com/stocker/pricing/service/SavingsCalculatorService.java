package com.stocker.pricing.service;

import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Pure calculation over a ShoppingListComparisonService result - no repository/IO dependencies.
 * savingsAmount(chain) = (selectedTotal - chainTotal) + discountAmount(chain). When every chain
 * ties on totalAmount, the first term is 0 for all of them, so savingsAmount reduces to exactly
 * discountAmount - showing discount-driven savings instead of a flat zero across the board.
 * Never clamped to zero: a chain pricier than the selected one legitimately shows negative
 * savings, since that's what "correctly calculate" requires.
 */
@Service
public class SavingsCalculatorService {

	public record ChainSavings(String chainId, BigDecimal savingsAmount) {
	}

	public List<ChainSavings> calculate(List<ChainTotal> chainTotals, String selectedChainId) {
		BigDecimal selectedTotal = chainTotals.stream()
				.filter(chainTotal -> chainTotal.chainId().equals(selectedChainId))
				.map(ChainTotal::totalAmount)
				.findFirst()
				.orElse(BigDecimal.ZERO);

		return chainTotals.stream()
				.map(chainTotal -> new ChainSavings(
						chainTotal.chainId(),
						selectedTotal.subtract(chainTotal.totalAmount())
								.add(chainTotal.discountAmount())
								.setScale(2, RoundingMode.HALF_UP)))
				.toList();
	}
}
