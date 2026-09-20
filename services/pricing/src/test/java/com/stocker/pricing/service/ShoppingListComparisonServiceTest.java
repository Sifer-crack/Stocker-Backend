package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.ShoppingListComparisonService.ChainTotal;
import com.stocker.pricing.service.ShoppingListComparisonService.ComparisonResult;
import com.stocker.pricing.service.ShoppingListComparisonService.RequestedItem;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingListComparisonServiceTest {

	private PriceStatsRepository priceStatsRepository;
	private ServiceAreaProperties serviceAreaProperties;
	private ShoppingListComparisonService service;

	@BeforeEach
	void setUp() {
		priceStatsRepository = mock(PriceStatsRepository.class);
		serviceAreaProperties = new ServiceAreaProperties();
		serviceAreaProperties.setSupportedRegions(List.of("auckland", "wellington"));
		service = new ShoppingListComparisonService(priceStatsRepository, serviceAreaProperties);
	}

	@Test
	void selectsCorrectCheapestChainForKnownItemTotals() {
		when(priceStatsRepository.findByItemIdIn(any())).thenReturn(List.of(
				stats("milk", "newworld-1", "newworld", "3.50"),
				stats("milk", "paknsave-1", "paknsave", "3.00"),
				stats("milk", "woolworths-1", "woolworths", "4.00")));

		ComparisonResult result = service.compare(List.of(new RequestedItem("milk", 1)), "auckland");

		assertEquals("paknsave", result.cheapestChainId());
		ChainTotal cheapest = chainTotal(result, "paknsave");
		assertEquals(new BigDecimal("3.00"), cheapest.totalAmount());
	}

	@Test
	void accuratelyCalculatesTotalCostPerSupermarketForGeneratedListAndExcludesPartialCoverageFromCheapest() {
		// paknsave is cheaper on milk alone, but doesn't carry bread at all - it must not win.
		when(priceStatsRepository.findByItemIdIn(any())).thenReturn(List.of(
				stats("milk", "newworld-1", "newworld", "3.50"),
				stats("bread", "newworld-2", "newworld", "4.00"),
				stats("milk", "paknsave-1", "paknsave", "3.00")));

		ComparisonResult result = service.compare(
				List.of(new RequestedItem("milk", 2), new RequestedItem("bread", 1)), "auckland");

		ChainTotal newworld = chainTotal(result, "newworld");
		assertEquals(new BigDecimal("11.00"), newworld.totalAmount(), "2 x 3.50 + 1 x 4.00");
		assertEquals(2, newworld.itemsPriced());
		assertTrue(newworld.unavailableItemIds().isEmpty());

		ChainTotal paknsave = chainTotal(result, "paknsave");
		assertEquals(new BigDecimal("6.00"), paknsave.totalAmount(), "2 x 3.00, bread unavailable");
		assertEquals(1, paknsave.itemsPriced());
		assertEquals(List.of("bread"), paknsave.unavailableItemIds());

		assertEquals("newworld", result.cheapestChainId(), "paknsave's lower raw total has only partial coverage");
	}

	@Test
	void whenMultipleStoresInOneChainCarryTheSameItemTheCheaperStoreWins() {
		when(priceStatsRepository.findByItemIdIn(any())).thenReturn(List.of(
				stats("milk", "newworld-1", "newworld", "3.50"),
				stats("milk", "newworld-2", "newworld", "3.20")));

		ComparisonResult result = service.compare(List.of(new RequestedItem("milk", 1)), "auckland");

		assertEquals(new BigDecimal("3.20"), chainTotal(result, "newworld").totalAmount());
	}

	@Test
	void fallsBackToLowestPartialTotalWhenNoChainHasFullCoverage() {
		when(priceStatsRepository.findByItemIdIn(any())).thenReturn(List.of(
				stats("milk", "newworld-1", "newworld", "3.50"),
				stats("bread", "paknsave-1", "paknsave", "2.00")));

		ComparisonResult result = service.compare(
				List.of(new RequestedItem("milk", 1), new RequestedItem("bread", 1)), "auckland");

		assertEquals("paknsave", result.cheapestChainId(), "no full coverage anywhere, so the lowest partial total wins");
	}

	@Test
	void discountAmountOnlyReflectsItemsCurrentlyOnPromo() {
		when(priceStatsRepository.findByItemIdIn(any())).thenReturn(List.of(
				statsBuilder("milk", "newworld-1", "newworld", "3.00").highestPrice(new BigDecimal("4.00"))
						.currentPromoFlag(true).build(),
				statsBuilder("bread", "newworld-2", "newworld", "2.00").highestPrice(new BigDecimal("2.00"))
						.currentPromoFlag(false).build()));

		ComparisonResult result = service.compare(
				List.of(new RequestedItem("milk", 2), new RequestedItem("bread", 1)), "auckland");

		assertEquals(new BigDecimal("2.00"), chainTotal(result, "newworld").discountAmount(), "2 x (4.00 - 3.00)");
	}

	@Test
	void unsupportedRegionThrowsServiceAreaException() {
		ServiceAreaException ex = assertThrows(ServiceAreaException.class,
				() -> service.compare(List.of(new RequestedItem("milk", 1)), "invalid-region"));

		assertEquals(ServiceAreaException.OUTSIDE_SERVICE_AREA_MESSAGE, ex.getMessage());
	}

	@Test
	void emptyItemsListThrowsSameServiceAreaExceptionWithoutQueryingRepository() {
		ServiceAreaException ex =
				assertThrows(ServiceAreaException.class, () -> service.compare(List.of(), "auckland"));

		assertEquals(ServiceAreaException.OUTSIDE_SERVICE_AREA_MESSAGE, ex.getMessage());
		verify(priceStatsRepository, never()).findByItemIdIn(any());
	}

	private static ChainTotal chainTotal(ComparisonResult result, String chainId) {
		return result.chainTotals().stream()
				.filter(chainTotal -> chainTotal.chainId().equals(chainId))
				.findFirst()
				.orElseThrow();
	}

	private static PriceStats stats(String itemId, String storeId, String chainId, String currentPrice) {
		return statsBuilder(itemId, storeId, chainId, currentPrice).build();
	}

	private static PriceStats.PriceStatsBuilder statsBuilder(
			String itemId, String storeId, String chainId, String currentPrice) {
		return PriceStats.builder()
				.itemId(itemId)
				.storeId(storeId)
				.chainId(chainId)
				.currency("NZD")
				.currentPrice(new BigDecimal(currentPrice))
				.lowestPrice(new BigDecimal(currentPrice))
				.highestPrice(new BigDecimal(currentPrice))
				.medianPrice(new BigDecimal(currentPrice))
				.currentPromoFlag(false);
	}
}
