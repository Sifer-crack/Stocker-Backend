package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.FulfilmentOption;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.service.fulfilment.FulfilmentMethod;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.service.FulfilmentFixtures.fees;
import static com.stocker.pricing.service.FulfilmentFixtures.properties;
import static com.stocker.pricing.service.fulfilment.FulfilmentMethod.CLICK_AND_COLLECT;
import static com.stocker.pricing.service.fulfilment.FulfilmentMethod.DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Fee maths, filtering, tie-break and recommendation, one item "x" at a known price per chain. */
class ShoppingListFulfilmentTest {

	private static final List<RequestedItem> ONE_X = List.of(new RequestedItem("x", 1));

	private PriceStatsRepository repository;
	private ServiceAreaProperties area;

	@BeforeEach
	void setUp() {
		repository = mock(PriceStatsRepository.class);
		area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland"));
	}

	private static PriceStats price(String chain, String price) {
		BigDecimal p = new BigDecimal(price);
		OffsetDateTime now = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);
		return PriceStats.builder().itemId("x").storeId(chain + ":").chainId(chain).currency("NZD").currentPrice(p)
				.lowestPrice(p).highestPrice(p).medianPrice(p).currentPromoFlag(false).priceHistory(List.of())
				.observationCount(1).firstObservedAt(now).lastObservedAt(now).build();
	}

	private ShoppingListComparisonService serviceWith(Map<String, com.stocker.pricing.service.fulfilment.FulfilmentProperties.ChainFees> fees,
			PriceStats... rows) {
		when(repository.findByItemIdIn(any())).thenReturn(List.of(rows));
		return new ShoppingListComparisonService(repository, area, properties(fees));
	}

	private static ChainTotal chain(ComparisonResult result, String id) {
		return result.chainTotals().stream().filter(c -> c.chainId().equals(id)).findFirst().orElseThrow();
	}

	private static FulfilmentOption option(ChainTotal total, FulfilmentMethod method) {
		return total.fulfilmentOptions().stream().filter(o -> o.method() == method).findFirst().orElse(null);
	}

	@Test
	void optionTotalIsItemsTotalPlusFee() {
		var service = serviceWith(Map.of("a", fees("6.00", "3.00", null)), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(option(a, DELIVERY).cost()).isEqualByComparingTo("6.00");
		assertThat(option(a, DELIVERY).totalAmount()).isEqualByComparingTo("16.00");
		assertThat(option(a, CLICK_AND_COLLECT).totalAmount()).isEqualByComparingTo("13.00");
		assertThat(a.totalAmount()).isEqualByComparingTo("10.00");
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("13.00");
	}

	@Test
	void aChainWithNoFeeConfigurationAtAllStaysEligibleWithNoFee() {
		var service = serviceWith(Map.of(), price("a", "10.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland");
		ChainTotal a = chain(result, "a");

		assertThat(a.fulfilmentOptions()).isEmpty();
		assertThat(a.bestOption()).isNull();
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("10.00");
		assertThat(result.cheapestChainId()).isEqualTo("a");
	}

	@Test
	void aChainWithAnEmptyFeeConfigurationStaysEligibleEvenWhenAMethodIsRequested() {
		var service = serviceWith(Map.of("a", fees(null, null, null), "b", fees("5.00", null, null)),
				price("a", "10.00"), price("b", "11.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland", Set.of(DELIVERY));

		assertThat(chain(result, "a").bestOption()).isNull();
		assertThat(chain(result, "a").totalWithFulfilment()).isEqualByComparingTo("10.00");
		assertThat(result.cheapestChainId()).isEqualTo("a");
		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("a", "b");
	}

	@Test
	void unconfiguredChainCompetesOnItemsTotalAgainstChainsWithFees() {
		var service = serviceWith(Map.of("fee", fees("5.00", null, null)), price("fee", "10.00"), price("free", "12.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("free");
		assertThat(chain(result, "fee").totalWithFulfilment()).isEqualByComparingTo("15.00");
	}

	@Test
	void chainLookupIsCaseInsensitive() {
		var service = serviceWith(Map.of("newworld", fees("2.00", null, null)), price("NewWorld", "10.00"));

		assertThat(chain(service.compare(ONE_X, "auckland"), "NewWorld").totalWithFulfilment()).isEqualByComparingTo("12.00");
	}

	@Test
	void deliveryAndClickAndCollectAreOfferedOnlyWhenTheirFeeIsConfigured() {
		var service = serviceWith(Map.of("a", fees("6.00", null, null), "b", fees(null, "3.00", null)),
				price("a", "10.00"), price("b", "10.00"));
		ComparisonResult result = service.compare(ONE_X, "auckland");

		assertThat(option(chain(result, "a"), DELIVERY).totalAmount()).isEqualByComparingTo("16.00");
		assertThat(option(chain(result, "a"), CLICK_AND_COLLECT)).isNull();
		assertThat(option(chain(result, "b"), CLICK_AND_COLLECT).totalAmount()).isEqualByComparingTo("13.00");
		assertThat(option(chain(result, "b"), DELIVERY)).isNull();
	}

	@Test
	void deliveryIsFreeWhenItemsTotalReachesTheThresholdButNotJustBelow() {
		var fees = Map.of("a", fees("6.00", null, "20.00"));

		var atThreshold = serviceWith(fees, price("a", "20.00")).compare(ONE_X, "auckland");
		assertThat(option(chain(atThreshold, "a"), DELIVERY).cost()).isEqualByComparingTo("0");
		assertThat(chain(atThreshold, "a").totalWithFulfilment()).isEqualByComparingTo("20.00");

		var below = serviceWith(fees, price("a", "19.99")).compare(ONE_X, "auckland");
		assertThat(option(chain(below, "a"), DELIVERY).cost()).isEqualByComparingTo("6.00");
		assertThat(chain(below, "a").totalWithFulfilment()).isEqualByComparingTo("25.99");
	}

	@Test
	void freeDeliveryThresholdDoesNotAffectOtherMethods() {
		var service = serviceWith(Map.of("a", fees("6.00", "2.00", "5.00")), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(option(a, DELIVERY).cost()).isEqualByComparingTo("0");
		assertThat(option(a, CLICK_AND_COLLECT).cost()).isEqualByComparingTo("2.00");
	}

	@Test
	void bestOptionIsTheCheapestAllowedMethod() {
		var service = serviceWith(Map.of("a", fees("9.00", "3.00", null)), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(a.bestOption().method()).isEqualTo(CLICK_AND_COLLECT);
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("13.00");
	}

	@Test
	void allowedMethodsRestrictTheOptionsConsidered() {
		var service = serviceWith(Map.of("a", fees("9.00", "3.00", null)), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland", Set.of(DELIVERY)), "a");

		assertThat(a.fulfilmentOptions()).extracting(FulfilmentOption::method).containsExactly(DELIVERY);
		assertThat(a.bestOption().method()).isEqualTo(DELIVERY);
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("19.00");
	}

	@Test
	void nullOrEmptyAllowedMethodsMeansAll() {
		var service = serviceWith(Map.of("a", fees("9.00", "3.00", null)), price("a", "10.00"));

		assertThat(chain(service.compare(ONE_X, "auckland", null), "a").fulfilmentOptions()).hasSize(2);
		assertThat(chain(service.compare(ONE_X, "auckland", Set.of()), "a").fulfilmentOptions()).hasSize(2);
	}

	@Test
	void twoArgumentCompareEqualsAllMethods() {
		var service = serviceWith(Map.of("a", fees("9.00", "3.00", null)), price("a", "10.00"));

		assertThat(service.compare(ONE_X, "auckland")).isEqualTo(service.compare(ONE_X, "auckland", Set.of()));
	}

	@Test
	void optionsAreListedInTieBreakOrderClickAndCollectThenDelivery() {
		var service = serviceWith(Map.of("a", fees("9.00", "3.00", null)), price("a", "10.00"));

		assertThat(chain(service.compare(ONE_X, "auckland"), "a").fulfilmentOptions())
				.extracting(FulfilmentOption::method).containsExactly(CLICK_AND_COLLECT, DELIVERY);
	}

	@Test
	void tiesBetweenMethodsGoToClickAndCollectBeforeDelivery() {
		var tie = serviceWith(Map.of("a", fees("2.00", "2.00", null)), price("a", "10.00"));
		assertThat(chain(tie.compare(ONE_X, "auckland"), "a").bestOption().method()).isEqualTo(CLICK_AND_COLLECT);

		var deliveryCheaper = serviceWith(Map.of("a", fees("1.00", "2.00", null)), price("a", "10.00"));
		assertThat(chain(deliveryCheaper.compare(ONE_X, "auckland"), "a").bestOption().method()).isEqualTo(DELIVERY);
	}

	@Test
	void aChainThatOffersMethodsButNoneAllowedIsLeftOutOfTheResultsEntirely() {
		// "a" is cheaper but only offers click and collect; "b" delivers.
		var service = serviceWith(Map.of("a", fees(null, "1.00", null), "b", fees("5.00", null, null)),
				price("a", "8.00"), price("b", "10.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland", Set.of(DELIVERY));

		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("b");
		assertThat(result.cheapestChainId()).isEqualTo("b");
	}

	@Test
	void anExcludedChainIsDroppedWhileAnUnconfiguredChainStaysWithNoFee() {
		var service = serviceWith(Map.of("a", fees(null, "1.00", null)), price("a", "5.00"), price("z", "20.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland", Set.of(DELIVERY));

		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("z");
		ChainTotal z = chain(result, "z");
		assertThat(z.bestOption()).isNull();
		assertThat(z.totalWithFulfilment()).isEqualByComparingTo("20.00");
		assertThat(result.cheapestChainId()).isEqualTo("z");
	}

	@Test
	void whenEveryChainIsExcludedTheResultIsEmptyWithNoRecommendation() {
		var service = serviceWith(Map.of("a", fees(null, "1.00", null)), price("a", "8.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland", Set.of(DELIVERY));

		assertThat(result.cheapestChainId()).isEmpty();
		assertThat(result.chainTotals()).isEmpty();
	}

	@Test
	void anExcludedCheaperChainCannotBeCheapestEvenWithoutFullCoverageElsewhere() {
		// "b" has only partial coverage; "a" (excluded) has full coverage. The fallback picks the best remaining chain.
		RequestedItem y = new RequestedItem("y", 1);
		PriceStats aX = price("a", "5.00");
		PriceStats aY = price("a", "5.00");
		aY.setItemId("y");
		PriceStats bX = price("b", "9.00");
		when(repository.findByItemIdIn(any())).thenReturn(List.of(aX, aY, bX));
		var service = new ShoppingListComparisonService(repository, area,
				properties(Map.of("a", fees(null, "1.00", null), "b", fees("2.00", null, null))));

		ComparisonResult result = service.compare(List.of(new RequestedItem("x", 1), y), "auckland", Set.of(DELIVERY));

		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("b");
		assertThat(result.cheapestChainId()).isEqualTo("b");
	}

	@Test
	void feesCanChangeWhichChainIsCheapest() {
		var service = serviceWith(Map.of("far", fees("5.00", null, null), "near", fees("0.00", null, null)),
				price("far", "10.00"), price("near", "12.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("near");
		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("near", "far");
	}

	@Test
	void chainsAreSortedByTotalWithFulfilmentThenItemsTotalThenChainId() {
		var service = serviceWith(Map.of(
						"a", fees("1.00", null, null), "b", fees("5.00", null, null), "c", fees("5.00", null, null),
						"d", fees("6.00", null, null)),
				price("a", "14.00"), price("b", "10.00"), price("c", "10.00"), price("d", "9.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland");

		// totals: a 15, b 15, c 15, d 15 -> items total ascending: d 9, b 10, c 10 (id), a 14
		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("d", "b", "c", "a");
		assertThat(result.cheapestChainId()).isEqualTo("d");
	}

	@Test
	void partialCoverageChainNeverWinsEvenWithCheaperFulfilment() {
		RequestedItem y = new RequestedItem("y", 1);
		PriceStats aX = price("a", "5.00");
		PriceStats bX = price("b", "5.00");
		PriceStats bY = price("b", "5.00");
		bY.setItemId("y");
		when(repository.findByItemIdIn(any())).thenReturn(List.of(aX, bX, bY));
		var service = new ShoppingListComparisonService(repository, area,
				properties(Map.of("a", fees("0.00", null, null), "b", fees("3.00", null, null))));

		ComparisonResult result = service.compare(List.of(new RequestedItem("x", 1), y), "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("b");
		assertThat(chain(result, "a").totalWithFulfilment()).isEqualByComparingTo("5.00");
	}

	@Test
	void sevenArgumentChainTotalHasNoFeeDefaults() {
		ChainTotal total = new ChainTotal("a", new BigDecimal("7.00"), "NZD", 1, 1, List.of(), BigDecimal.ZERO);

		assertThat(total.fulfilmentOptions()).isEmpty();
		assertThat(total.bestOption()).isNull();
		assertThat(total.totalWithFulfilment()).isEqualByComparingTo("7.00");
	}
}
