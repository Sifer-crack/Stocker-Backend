package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Compares a shopping list's total cost across supermarket chains using price_stats (never
 * price_records directly - see PriceStatsService). Chains are only eligible to be the recommended
 * cheapest option if they carry every requested item ("full coverage"); a chain missing the
 * priciest items must never win on a partial total. If no chain has full coverage, the lowest
 * partial total is used instead so a recommendation is still returned.
 */
@Service
@RequiredArgsConstructor
public class ShoppingListComparisonService {

	private final PriceStatsRepository priceStatsRepository;
	private final ServiceAreaProperties serviceAreaProperties;

	public record RequestedItem(String itemId, int quantity) {
	}

	public record ChainTotal(
			String chainId,
			BigDecimal totalAmount,
			String currency,
			int itemsPriced,
			int itemsRequested,
			List<String> unavailableItemIds,
			BigDecimal discountAmount) {

		public boolean hasFullCoverage() {
			return itemsPriced == itemsRequested;
		}
	}

	public record ComparisonResult(List<ChainTotal> chainTotals, String cheapestChainId) {
	}

	public ComparisonResult compare(List<RequestedItem> items, String region) {
		if (items == null || items.isEmpty()) {
			throw ServiceAreaException.outsideServiceArea();
		}
		if (!serviceAreaProperties.isSupported(region)) {
			throw ServiceAreaException.outsideServiceArea();
		}

		Map<String, Integer> quantitiesByItemId = mergeDuplicateItems(items);
		List<PriceStats> priceStats = priceStatsRepository.findByItemIdIn(quantitiesByItemId.keySet());
		Map<String, Map<String, PriceStats>> cheapestRowByChainThenItem = groupByChainThenCheapestItem(priceStats);

		List<ChainTotal> chainTotals = new ArrayList<>();
		for (Map.Entry<String, Map<String, PriceStats>> chainEntry : cheapestRowByChainThenItem.entrySet()) {
			chainTotals.add(toChainTotal(chainEntry.getKey(), chainEntry.getValue(), quantitiesByItemId));
		}
		chainTotals.sort(Comparator.comparing(ChainTotal::totalAmount));

		String cheapestChainId = chainTotals.stream()
				.filter(ChainTotal::hasFullCoverage)
				.findFirst()
				.map(ChainTotal::chainId)
				.orElseGet(() -> chainTotals.isEmpty() ? "" : chainTotals.get(0).chainId());

		return new ComparisonResult(chainTotals, cheapestChainId);
	}

	private static Map<String, Integer> mergeDuplicateItems(List<RequestedItem> items) {
		Map<String, Integer> quantitiesByItemId = new LinkedHashMap<>();
		for (RequestedItem item : items) {
			quantitiesByItemId.merge(item.itemId(), item.quantity(), Integer::sum);
		}
		return quantitiesByItemId;
	}

	/** Within one chain, when the same item has multiple stores (rows), keeps the cheapest. */
	private static Map<String, Map<String, PriceStats>> groupByChainThenCheapestItem(List<PriceStats> priceStats) {
		Map<String, Map<String, PriceStats>> byChainThenItem = new LinkedHashMap<>();
		for (PriceStats stats : priceStats) {
			Map<String, PriceStats> byItem = byChainThenItem.computeIfAbsent(stats.getChainId(), c -> new LinkedHashMap<>());
			PriceStats existing = byItem.get(stats.getItemId());
			if (existing == null || stats.getCurrentPrice().compareTo(existing.getCurrentPrice()) < 0) {
				byItem.put(stats.getItemId(), stats);
			}
		}
		return byChainThenItem;
	}

	private static ChainTotal toChainTotal(
			String chainId, Map<String, PriceStats> cheapestRowByItem, Map<String, Integer> quantitiesByItemId) {
		BigDecimal total = BigDecimal.ZERO;
		BigDecimal discount = BigDecimal.ZERO;
		int itemsPriced = 0;
		List<String> unavailableItemIds = new ArrayList<>();
		String currency = null;

		for (Map.Entry<String, Integer> requested : quantitiesByItemId.entrySet()) {
			String itemId = requested.getKey();
			BigDecimal quantity = BigDecimal.valueOf(requested.getValue());
			PriceStats stats = cheapestRowByItem.get(itemId);
			if (stats == null) {
				unavailableItemIds.add(itemId);
				continue;
			}
			itemsPriced++;
			total = total.add(stats.getCurrentPrice().multiply(quantity));
			if (currency == null) {
				currency = stats.getCurrency();
			}
			if (stats.isCurrentPromoFlag()) {
				discount = discount.add(stats.getHighestPrice().subtract(stats.getCurrentPrice()).multiply(quantity));
			}
		}

		return new ChainTotal(
				chainId,
				total.setScale(2, RoundingMode.HALF_UP),
				currency,
				itemsPriced,
				quantitiesByItemId.size(),
				unavailableItemIds,
				discount.setScale(2, RoundingMode.HALF_UP));
	}
}
