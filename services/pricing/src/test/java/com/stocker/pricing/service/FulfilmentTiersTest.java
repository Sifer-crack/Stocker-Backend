package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.SavingsCalculatorService.ChainSavings;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.FulfilmentOption;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.service.fulfilment.FulfilmentMethod;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.ChainFees;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.stocker.pricing.service.FulfilmentFixtures.island;
import static com.stocker.pricing.service.FulfilmentFixtures.tiered;
import static com.stocker.pricing.service.FulfilmentFixtures.tiers;
import static com.stocker.pricing.service.fulfilment.FulfilmentMethod.CLICK_AND_COLLECT;
import static com.stocker.pricing.service.fulfilment.FulfilmentMethod.DELIVERY;
import static com.stocker.pricing.service.fulfilment.FulfilmentMethod.IN_STORE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tiered fees and island overrides, using the owner-supplied 2026 ladders as acceptance data. */
class FulfilmentTiersTest {

	private static final List<RequestedItem> ONE_X = List.of(new RequestedItem("x", 1));

	private PriceStatsRepository repository;
	private ServiceAreaProperties area;

	@BeforeEach
	void setUp() {
		repository = mock(PriceStatsRepository.class);
		area = new ServiceAreaProperties();
		area.setSupportedRegions(List.of("auckland", "wellington", "christchurch", "dunedin"));
	}

	private static ChainFees newWorld() {
		ChainFees fees = tiered("0.00", tiers("0", "14.00", "200", "9.00"), tiers("0", "5.00", "50", "0.00"));
		island(fees, "south", tiers("0", "15.00", "200", "10.00"), tiers("0", "3.50"));
		return fees;
	}

	private static ChainFees paknsave() {
		return tiered("0.00", null, tiers("0", "5.00"));
	}

	private static ChainFees woolworths() {
		return tiered("0.00", tiers("0", "14.00", "200", "9.00"), tiers("0", "3.50"));
	}

	private static FulfilmentProperties properties(Map<String, ChainFees> chains) {
		FulfilmentProperties p = FulfilmentFixtures.properties(chains);
		Map<String, String> islands = new LinkedHashMap<>();
		islands.put("auckland", "north");
		islands.put("wellington", "north");
		islands.put("christchurch", "south");
		p.setRegionIslands(islands);
		return p;
	}

	private static PriceStats price(String chain, String price) {
		BigDecimal p = new BigDecimal(price);
		OffsetDateTime now = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);
		return PriceStats.builder().itemId("x").storeId(chain + ":").chainId(chain).currency("NZD").currentPrice(p)
				.lowestPrice(p).highestPrice(p).medianPrice(p).currentPromoFlag(false).priceHistory(List.of())
				.observationCount(1).firstObservedAt(now).lastObservedAt(now).build();
	}

	private ComparisonResult compare(Map<String, ChainFees> chains, String region, Set<FulfilmentMethod> allowed,
			PriceStats... rows) {
		when(repository.findByItemIdIn(any())).thenReturn(List.of(rows));
		return new ShoppingListComparisonService(repository, area, properties(chains)).compare(ONE_X, region, allowed);
	}

	private ComparisonResult nw(String itemsTotal, String region, Set<FulfilmentMethod> allowed) {
		return compare(Map.of("newworld", newWorld()), region, allowed, price("newworld", itemsTotal));
	}

	private static ChainTotal chain(ComparisonResult r, String id) {
		return r.chainTotals().stream().filter(c -> c.chainId().equals(id)).findFirst().orElseThrow();
	}

	private static FulfilmentOption option(ChainTotal t, FulfilmentMethod m) {
		return t.fulfilmentOptions().stream().filter(o -> o.method() == m).findFirst().orElse(null);
	}

	private void assertCost(ComparisonResult r, String chain, FulfilmentMethod m, String cost) {
		FulfilmentOption o = option(chain(r, chain), m);
		assertThat(o).as("%s offered by %s", m, chain).isNotNull();
		assertThat(o.cost()).isEqualByComparingTo(cost);
	}

	@Test
	void deliveryTierBoundaryIsInclusiveAt200() {
		assertCost(nw("199.99", "auckland", Set.of()), "newworld", DELIVERY, "14.00");
		assertCost(nw("200.00", "auckland", Set.of()), "newworld", DELIVERY, "9.00");
		assertCost(nw("200.01", "auckland", Set.of()), "newworld", DELIVERY, "9.00");
	}

	@Test
	void clickAndCollectTierBoundaryIsInclusiveAt50() {
		assertCost(nw("49.99", "auckland", Set.of()), "newworld", CLICK_AND_COLLECT, "5.00");
		assertCost(nw("50.00", "auckland", Set.of()), "newworld", CLICK_AND_COLLECT, "0.00");
	}

	@Test
	void smallestBasketUsesTheFirstTier() {
		assertCost(nw("0.01", "auckland", Set.of()), "newworld", DELIVERY, "14.00");
	}

	@Test
	void newWorldNorthLaddersApplyToAucklandAndWellington() {
		for (String region : List.of("auckland", "wellington", "Wellington", " AUCKLAND ")) {
			assertCost(nw("30.00", region, Set.of()), "newworld", DELIVERY, "14.00");
			assertCost(nw("30.00", region, Set.of()), "newworld", CLICK_AND_COLLECT, "5.00");
			assertCost(nw("200.00", region, Set.of()), "newworld", DELIVERY, "9.00");
			assertCost(nw("60.00", region, Set.of()), "newworld", CLICK_AND_COLLECT, "0.00");
		}
	}

	@Test
	void newWorldSouthOverridesBothMethodsForChristchurch() {
		assertCost(nw("199.99", "christchurch", Set.of()), "newworld", DELIVERY, "15.00");
		assertCost(nw("200.00", "christchurch", Set.of()), "newworld", DELIVERY, "10.00");
		// flat 3.50, no waiver at 50
		assertCost(nw("49.99", "christchurch", Set.of()), "newworld", CLICK_AND_COLLECT, "3.50");
		assertCost(nw("50.00", "christchurch", Set.of()), "newworld", CLICK_AND_COLLECT, "3.50");
		assertCost(nw("500.00", "christchurch", Set.of()), "newworld", CLICK_AND_COLLECT, "3.50");
	}

	@Test
	void supportedRegionWithoutAnIslandMappingUsesChainDefaults() {
		assertCost(nw("30.00", "dunedin", Set.of()), "newworld", DELIVERY, "14.00");
		assertCost(nw("30.00", "dunedin", Set.of()), "newworld", CLICK_AND_COLLECT, "5.00");
	}

	@Test
	void islandOverrideOfOneMethodLeavesTheOtherOnTheDefault() {
		ChainFees fees = tiered("0.00", tiers("0", "14.00"), tiers("0", "5.00"));
		island(fees, "south", null, tiers("0", "2.00"));
		var r = compare(Map.of("a", fees), "christchurch", Set.of(), price("a", "30.00"));
		assertCost(r, "a", DELIVERY, "14.00");
		assertCost(r, "a", CLICK_AND_COLLECT, "2.00");
	}

	@Test
	void paknsaveHasNoDeliveryEvenWhenExplicitlyAllowed() {
		var r = compare(Map.of("paknsave", paknsave()), "auckland", Set.of(DELIVERY, CLICK_AND_COLLECT),
				price("paknsave", "80.00"));
		ChainTotal ps = chain(r, "paknsave");
		assertThat(option(ps, DELIVERY)).isNull();
		assertThat(ps.fulfilmentOptions()).extracting(FulfilmentOption::method).containsExactly(CLICK_AND_COLLECT);
		assertThat(ps.bestOption().method()).isEqualTo(CLICK_AND_COLLECT);
		assertThat(ps.totalWithFulfilment()).isEqualByComparingTo("85.00");
	}

	@Test
	void paknsaveWithOnlyDeliveryAllowedHasNoOptionAndIsNeverRecommended() {
		var r = compare(Map.of("paknsave", paknsave(), "woolworths", woolworths()), "auckland", Set.of(DELIVERY),
				price("paknsave", "10.00"), price("woolworths", "30.00"));
		assertThat(chain(r, "paknsave").bestOption()).isNull();
		assertThat(r.cheapestChainId()).isEqualTo("woolworths");
	}

	@Test
	void woolworthsLadderAndFlatClickAndCollect() {
		Map<String, ChainFees> chains = Map.of("woolworths", woolworths());
		assertCost(compare(chains, "auckland", Set.of(), price("woolworths", "199.99")), "woolworths", DELIVERY, "14.00");
		assertCost(compare(chains, "auckland", Set.of(), price("woolworths", "200.00")), "woolworths", DELIVERY, "9.00");
		assertCost(compare(chains, "auckland", Set.of(), price("woolworths", "400.00")), "woolworths", CLICK_AND_COLLECT, "3.50");
		assertCost(compare(chains, "christchurch", Set.of(), price("woolworths", "30.00")), "woolworths", DELIVERY, "14.00");
	}

	@Test
	void chainWithOnlySomeMethodsConfiguredOffersJustThose() {
		ChainFees onlyDelivery = tiered("0.00", tiers("0", "7.00"), null);
		var r = compare(Map.of("a", onlyDelivery), "auckland", Set.of(), price("a", "10.00"));
		assertThat(r.chainTotals().get(0).fulfilmentOptions()).extracting(FulfilmentOption::method)
				.containsExactly(IN_STORE, DELIVERY);
		ChainFees emptyLadders = tiered("0.00", List.of(), List.of());
		var r2 = compare(Map.of("a", emptyLadders), "auckland", Set.of(), price("a", "10.00"));
		assertThat(r2.chainTotals().get(0).fulfilmentOptions()).extracting(FulfilmentOption::method).containsExactly(IN_STORE);
	}

	@Test
	void allowedMethodsFilteringStillWorksWithTiers() {
		var r = nw("30.00", "auckland", Set.of(DELIVERY));
		assertThat(chain(r, "newworld").fulfilmentOptions()).extracting(FulfilmentOption::method).containsExactly(DELIVERY);
		assertThat(chain(r, "newworld").totalWithFulfilment()).isEqualByComparingTo("44.00");
		var r2 = nw("30.00", "auckland", Set.of(CLICK_AND_COLLECT, IN_STORE));
		assertThat(chain(r2, "newworld").bestOption().method()).isEqualTo(IN_STORE);
	}

	@Test
	void roundingStaysAtTwoDecimals() {
		var r = nw("49.999", "auckland", Set.of(CLICK_AND_COLLECT));
		// items total rounds to 50.00 first, which reaches the waiver
		assertThat(chain(r, "newworld").totalAmount()).isEqualByComparingTo("50.00");
		assertThat(chain(r, "newworld").totalWithFulfilment()).isEqualByComparingTo("50.00");
	}

	@Test
	void recommendationFlipsAcrossNewWorldsFiftyDollarWaiver() {
		// woolworths C&C is always 3.50. newworld C&C is 5.00 under 50, free from 50.
		Map<String, ChainFees> chains = Map.of("newworld", newWorld(), "woolworths", woolworths());
		Set<FulfilmentMethod> cc = Set.of(CLICK_AND_COLLECT);

		// under: newworld 48.00+5.00 = 53.00 vs woolworths 50.00+3.50 = 53.50 -> newworld
		var under = compare(chains, "auckland", cc, price("newworld", "48.00"), price("woolworths", "50.00"));
		assertThat(under.cheapestChainId()).isEqualTo("newworld");
		assertThat(chain(under, "newworld").totalWithFulfilment()).isEqualByComparingTo("53.00");

		// the same prices a little higher: newworld 49.99+5.00 = 54.99 vs woolworths 50.00+3.50 = 53.50 -> woolworths
		var justUnder = compare(chains, "auckland", cc, price("newworld", "49.99"), price("woolworths", "50.00"));
		assertThat(justUnder.cheapestChainId()).isEqualTo("woolworths");

		// newworld 50.00 + 0 = 50.00 vs woolworths 50.00+3.50 -> newworld (waiver reached)
		var over = compare(chains, "auckland", cc, price("newworld", "50.00"), price("woolworths", "50.00"));
		assertThat(over.cheapestChainId()).isEqualTo("newworld");
		assertThat(chain(over, "newworld").totalWithFulfilment()).isEqualByComparingTo("50.00");
		assertThat(chain(over, "newworld").bestOption().method()).isEqualTo(CLICK_AND_COLLECT);
	}

	@Test
	void bestMethodChangesAcrossTheThresholdWithinOneChain() {
		// under 50: in_store (0) beats C&C (5); at 50, C&C (0) ties in_store and in_store wins the tie.
		assertThat(chain(nw("49.99", "auckland", Set.of()), "newworld").bestOption().method()).isEqualTo(IN_STORE);
		assertThat(chain(nw("50.00", "auckland", Set.of()), "newworld").bestOption().method()).isEqualTo(IN_STORE);
		// with travel cost, the waiver makes C&C best exactly at 50.
		ChainFees fees = tiered("2.00", null, tiers("0", "5.00", "50", "0.00"));
		var under = compare(Map.of("a", fees), "auckland", Set.of(), price("a", "49.99"));
		var at = compare(Map.of("a", fees), "auckland", Set.of(), price("a", "50.00"));
		assertThat(under.chainTotals().get(0).bestOption().method()).isEqualTo(IN_STORE);
		assertThat(at.chainTotals().get(0).bestOption().method()).isEqualTo(CLICK_AND_COLLECT);
	}

	@Test
	void savingsAreConsistentWithTieredTotals() {
		Map<String, ChainFees> chains = Map.of("newworld", newWorld(), "woolworths", woolworths());
		var r = compare(chains, "christchurch", Set.of(CLICK_AND_COLLECT), price("newworld", "40.00"), price("woolworths", "40.00"));
		// south C&C: newworld 3.50 -> 43.50; woolworths 3.50 -> 43.50 ; make woolworths selected
		List<ChainSavings> savings = new SavingsCalculatorService().calculate(r.chainTotals(), "woolworths");
		for (ChainSavings s : savings) {
			ChainTotal t = chain(r, s.chainId());
			BigDecimal expected = chain(r, "woolworths").totalWithFulfilment().subtract(t.totalWithFulfilment())
					.add(t.discountAmount());
			assertThat(s.savingsAmount()).isEqualByComparingTo(expected);
		}

		var north = compare(chains, "auckland", Set.of(CLICK_AND_COLLECT), price("newworld", "55.00"), price("woolworths", "55.00"));
		List<ChainSavings> northSavings = new SavingsCalculatorService().calculate(north.chainTotals(), "woolworths");
		// newworld waived (55.00) vs woolworths 58.50 -> newworld saves 3.50
		assertThat(northSavings.stream().filter(s -> s.chainId().equals("newworld")).findFirst().orElseThrow().savingsAmount())
				.isEqualByComparingTo("3.50");
	}

	@Test
	void chainWithNoConfigIsStillInStoreAtZero() {
		var r = compare(Map.of("newworld", newWorld()), "auckland", Set.of(), price("unknownchain", "12.00"));
		ChainTotal t = chain(r, "unknownchain");
		assertThat(t.fulfilmentOptions()).extracting(FulfilmentOption::method).containsExactly(IN_STORE);
		assertThat(t.totalWithFulfilment()).isEqualByComparingTo("12.00");
	}
}
