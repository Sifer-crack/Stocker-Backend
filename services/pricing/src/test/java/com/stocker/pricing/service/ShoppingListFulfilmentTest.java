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
import static com.stocker.pricing.service.fulfilment.FulfilmentMethod.IN_STORE;
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
	void inStoreCostIsTheChainsTravelCostAdded() {
		var service = serviceWith(Map.of("a", fees("4.00", null, null, null)), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(option(a, IN_STORE).cost()).isEqualByComparingTo("4.00");
		assertThat(option(a, IN_STORE).totalAmount()).isEqualByComparingTo("14.00");
		assertThat(a.totalAmount()).isEqualByComparingTo("10.00");
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("14.00");
	}

	@Test
	void aChainWithNoConfiguredFeesIsInStoreAtZeroCost() {
		var service = serviceWith(Map.of(), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(a.fulfilmentOptions()).hasSize(1);
		assertThat(a.bestOption().method()).isEqualTo(IN_STORE);
		assertThat(a.bestOption().cost()).isEqualByComparingTo("0");
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("10.00");
	}

	@Test
	void chainLookupIsCaseInsensitive() {
		var service = serviceWith(Map.of("newworld", fees("2.00", null, null, null)), price("NewWorld", "10.00"));

		assertThat(chain(service.compare(ONE_X, "auckland"), "NewWorld").totalWithFulfilment()).isEqualByComparingTo("12.00");
	}

	@Test
	void deliveryAndClickAndCollectAreOfferedOnlyWhenTheirFeeIsConfigured() {
		var service = serviceWith(Map.of("a", fees("1.00", "6.00", null, null), "b", fees("1.00", null, "3.00", null)),
				price("a", "10.00"), price("b", "10.00"));
		ComparisonResult result = service.compare(ONE_X, "auckland");

		assertThat(option(chain(result, "a"), DELIVERY).totalAmount()).isEqualByComparingTo("16.00");
		assertThat(option(chain(result, "a"), CLICK_AND_COLLECT)).isNull();
		assertThat(option(chain(result, "b"), CLICK_AND_COLLECT).totalAmount()).isEqualByComparingTo("13.00");
		assertThat(option(chain(result, "b"), DELIVERY)).isNull();
	}

	@Test
	void deliveryIsFreeWhenItemsTotalReachesTheThresholdButNotJustBelow() {
		var fees = Map.of("a", fees("50.00", "6.00", null, "20.00"));

		var atThreshold = serviceWith(fees, price("a", "20.00")).compare(ONE_X, "auckland");
		assertThat(option(chain(atThreshold, "a"), DELIVERY).cost()).isEqualByComparingTo("0");
		assertThat(chain(atThreshold, "a").totalWithFulfilment()).isEqualByComparingTo("20.00");

		var below = serviceWith(fees, price("a", "19.99")).compare(ONE_X, "auckland");
		assertThat(option(chain(below, "a"), DELIVERY).cost()).isEqualByComparingTo("6.00");
		assertThat(chain(below, "a").totalWithFulfilment()).isEqualByComparingTo("25.99");
	}

	@Test
	void freeDeliveryThresholdDoesNotAffectOtherMethods() {
		var service = serviceWith(Map.of("a", fees("1.00", "6.00", "2.00", "5.00")), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(option(a, IN_STORE).cost()).isEqualByComparingTo("1.00");
		assertThat(option(a, CLICK_AND_COLLECT).cost()).isEqualByComparingTo("2.00");
	}

	@Test
	void bestOptionIsTheCheapestAllowedMethod() {
		var service = serviceWith(Map.of("a", fees("4.00", "9.00", "3.00", null)), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland"), "a");

		assertThat(a.bestOption().method()).isEqualTo(CLICK_AND_COLLECT);
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("13.00");
	}

	@Test
	void allowedMethodsRestrictTheOptionsConsidered() {
		var service = serviceWith(Map.of("a", fees("4.00", "9.00", "3.00", null)), price("a", "10.00"));

		ChainTotal a = chain(service.compare(ONE_X, "auckland", Set.of(DELIVERY)), "a");

		assertThat(a.fulfilmentOptions()).extracting(FulfilmentOption::method).containsExactly(DELIVERY);
		assertThat(a.bestOption().method()).isEqualTo(DELIVERY);
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("19.00");
	}

	@Test
	void nullOrEmptyAllowedMethodsMeansAll() {
		var service = serviceWith(Map.of("a", fees("4.00", "9.00", "3.00", null)), price("a", "10.00"));

		assertThat(chain(service.compare(ONE_X, "auckland", null), "a").fulfilmentOptions()).hasSize(3);
		assertThat(chain(service.compare(ONE_X, "auckland", Set.of()), "a").fulfilmentOptions()).hasSize(3);
	}

	@Test
	void twoArgumentCompareEqualsAllMethods() {
		var service = serviceWith(Map.of("a", fees("4.00", "9.00", "3.00", null)), price("a", "10.00"));

		assertThat(service.compare(ONE_X, "auckland")).isEqualTo(service.compare(ONE_X, "auckland", Set.of()));
	}

	@Test
	void tiesBetweenMethodsGoInStoreThenClickAndCollectThenDelivery() {
		var allTie = serviceWith(Map.of("a", fees("2.00", "2.00", "2.00", null)), price("a", "10.00"));
		assertThat(chain(allTie.compare(ONE_X, "auckland"), "a").bestOption().method()).isEqualTo(IN_STORE);

		var ccVsDelivery = serviceWith(Map.of("a", fees("9.00", "2.00", "2.00", null)), price("a", "10.00"));
		assertThat(chain(ccVsDelivery.compare(ONE_X, "auckland"), "a").bestOption().method()).isEqualTo(CLICK_AND_COLLECT);
	}

	@Test
	void aChainWithNoAllowedMethodIsReturnedUnrecommendedWithItemsTotal() {
		// "a" is cheaper but offers no delivery; "b" delivers.
		var service = serviceWith(Map.of("a", fees("0", null, null, null), "b", fees("0", "5.00", null, null)),
				price("a", "8.00"), price("b", "10.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland", Set.of(DELIVERY));

		ChainTotal a = chain(result, "a");
		assertThat(a.bestOption()).isNull();
		assertThat(a.fulfilmentOptions()).isEmpty();
		assertThat(a.totalWithFulfilment()).isEqualByComparingTo("8.00");
		assertThat(result.cheapestChainId()).isEqualTo("b");
		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("b", "a");
	}

	@Test
	void whenNoChainCanFulfilTheRequestThereIsNoRecommendation() {
		var service = serviceWith(Map.of("a", fees("0", null, null, null)), price("a", "8.00"));

		assertThat(service.compare(ONE_X, "auckland", Set.of(DELIVERY)).cheapestChainId()).isEmpty();
	}

	@Test
	void feesCanChangeWhichChainIsCheapest() {
		var service = serviceWith(Map.of("far", fees("5.00", null, null, null), "near", fees("0.00", null, null, null)),
				price("far", "10.00"), price("near", "12.00"));

		ComparisonResult result = service.compare(ONE_X, "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("near");
		assertThat(result.chainTotals()).extracting(ChainTotal::chainId).containsExactly("near", "far");
	}

	@Test
	void chainsAreSortedByTotalWithFulfilmentThenItemsTotalThenChainId() {
		// all 15.00 with fees: b and c tie on items total too -> chain id; a has higher items total but lower fee
		var service = serviceWith(Map.of(
						"a", fees("1.00", null, null, null), "b", fees("5.00", null, null, null), "c", fees("5.00", null, null, null),
						"d", fees("6.00", null, null, null)),
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
				properties(Map.of("a", fees("0", null, null, null), "b", fees("3.00", null, null, null))));

		ComparisonResult result = service.compare(List.of(new RequestedItem("x", 1), y), "auckland");

		assertThat(result.cheapestChainId()).isEqualTo("b");
		assertThat(chain(result, "a").totalWithFulfilment()).isEqualByComparingTo("5.00");
	}

	@Test
	void sevenArgumentChainTotalHasZeroFeeDefaults() {
		ChainTotal total = new ChainTotal("a", new BigDecimal("7.00"), "NZD", 1, 1, List.of(), BigDecimal.ZERO);

		assertThat(total.fulfilmentOptions()).hasSize(1);
		assertThat(total.bestOption().method()).isEqualTo(IN_STORE);
		assertThat(total.bestOption().cost()).isEqualByComparingTo("0");
		assertThat(total.bestOption().totalAmount()).isEqualByComparingTo("7.00");
		assertThat(total.totalWithFulfilment()).isEqualByComparingTo("7.00");
	}
}
