package com.stocker.pricing.acceptance;

import com.stocker.pricing.model.PriceStats;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The known prices every acceptance test works from, so each expected number can be checked by hand.
 *
 * <pre>
 *             milk    eggs    butter
 * paknsave    3.00    6.00    5.00
 * woolworths  3.20    6.10    5.20   (butter on special, was 6.00)
 * newworld    3.50    6.50    5.50
 * </pre>
 * List "2 milk, 1 eggs, 1 butter": paknsave 17.00, woolworths 17.70, newworld 19.00.
 */
final class PriceFixtures {

	static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);

	private PriceFixtures() {
	}

	static PriceStats price(String chain, String item, String price) {
		return price(chain, item, price, price, false);
	}

	/** {@code was} is the highest price seen; on a special the discount is (was - price) per unit. */
	static PriceStats price(String chain, String item, String price, String was, boolean promo) {
		return PriceStats.builder().itemId(item).storeId(chain + ":").chainId(chain).currency("NZD")
				.currentPrice(new BigDecimal(price)).lowestPrice(new BigDecimal(price)).highestPrice(new BigDecimal(was))
				.medianPrice(new BigDecimal(price)).currentPromoFlag(promo).priceHistory(List.of())
				.observationCount(1).firstObservedAt(NOW).lastObservedAt(NOW).build();
	}

	static List<PriceStats> weeklyShop() {
		return List.of(
				price("paknsave", "milk", "3.00"), price("paknsave", "eggs", "6.00"), price("paknsave", "butter", "5.00"),
				price("woolworths", "milk", "3.20"), price("woolworths", "eggs", "6.10"),
				price("woolworths", "butter", "5.20", "6.00", true),
				price("newworld", "milk", "3.50"), price("newworld", "eggs", "6.50"), price("newworld", "butter", "5.50"));
	}

	static Map<String, Integer> weeklyList() {
		return Map.of("milk", 2, "eggs", 1, "butter", 1);
	}
}
