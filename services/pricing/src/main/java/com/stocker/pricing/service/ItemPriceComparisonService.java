package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.service.servicearea.ServiceAreaException;
import com.stocker.pricing.service.servicearea.ServiceAreaProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Backs the {@code CompareItemPrices} RPC: for each requested item, looks its prices up through
 * {@link PriceSearchService#search} (cache-aside, bounded live fetch, async refresh on a miss) and
 * reduces them to the latest usable price per (chain, store), cheapest first. A miss is a normal
 * "not found yet" result, not an error - the async refresh publishes PriceRecordCaptured events
 * under the same itemId, which is how callers backfill later.
 */
@Service
@RequiredArgsConstructor
public class ItemPriceComparisonService {

	private static final Logger log = LoggerFactory.getLogger(ItemPriceComparisonService.class);

	private final PriceSearchService priceSearchService;
	private final ServiceAreaProperties serviceAreaProperties;

	public record ItemQuery(String itemId, String searchTerm, String category) {
	}

	public record StoreQuote(String chainId, String storeId, BigDecimal priceAmount, String currency,
			boolean promoFlag, OffsetDateTime capturedAt) {
	}

	public record ItemComparison(String itemId, List<StoreQuote> prices) {

		public boolean found() {
			return !prices.isEmpty();
		}

		public StoreQuote cheapest() {
			return prices.isEmpty() ? null : prices.get(0);
		}
	}

	/** A blank region skips the service-area check; a non-blank one must be supported. */
	public List<ItemComparison> compare(List<ItemQuery> items, String region) {
		if (StringUtils.hasText(region) && !serviceAreaProperties.isSupported(region)) {
			throw ServiceAreaException.outsideServiceArea();
		}
		return items.stream().map(this::compareOne).toList();
	}

	private ItemComparison compareOne(ItemQuery query) {
		if (!StringUtils.hasText(query.itemId()) || !StringUtils.hasText(query.searchTerm())) {
			return new ItemComparison(query.itemId(), List.of());
		}
		try {
			List<PriceRecord> records =
					priceSearchService.search(query.searchTerm(), query.itemId(), List.of(), query.category());
			return new ItemComparison(query.itemId(), latestUsablePricePerStore(records));
		} catch (RuntimeException e) {
			// One item failing must not fail the whole RPC; the caller treats this as "not found yet".
			log.warn("Price comparison failed for itemId={}, term={}", query.itemId(), query.searchTerm(), e);
			return new ItemComparison(query.itemId(), List.of());
		}
	}

	private static List<StoreQuote> latestUsablePricePerStore(List<PriceRecord> records) {
		Map<String, PriceRecord> latestByStore = new LinkedHashMap<>();
		for (PriceRecord record : records) {
			if (record.getPriceAmount() == null || record.getPriceAmount().signum() <= 0) {
				continue;
			}
			latestByStore.merge(record.getChainId() + "|" + record.getStoreId(), record,
					(existing, candidate) -> isNewer(candidate, existing) ? candidate : existing);
		}
		return latestByStore.values().stream()
				.map(record -> new StoreQuote(record.getChainId(), record.getStoreId(), record.getPriceAmount(),
						record.getCurrency(), record.isPromoFlag(), record.getCapturedAt()))
				.sorted(Comparator.comparing(StoreQuote::priceAmount)
						.thenComparing(StoreQuote::chainId)
						.thenComparing(StoreQuote::storeId))
				.toList();
	}

	private static boolean isNewer(PriceRecord candidate, PriceRecord existing) {
		if (candidate.getCapturedAt() == null) {
			return false;
		}
		return existing.getCapturedAt() == null || candidate.getCapturedAt().isAfter(existing.getCapturedAt());
	}
}
