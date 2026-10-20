package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceStatsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of {@link PriceStatsService}: recomputes the price_stats row of one (item, store) from a
 * new price_records row. Its own bean so the transaction ends when this method returns, before anything else runs.
 */
@Service
@RequiredArgsConstructor
public class PriceStatsWriter {

	/** Bounds price_history's JSONB size - old observations roll off, oldest first. */
	private static final int MAX_HISTORY_SIZE = 500;

	

	private final PriceStatsRepository priceStatsRepository;

	@Transactional
	public void record(PriceRecord record) {
		PriceStats stats = priceStatsRepository.findByItemIdAndStoreId(record.getItemId(), record.getStoreId())
				.orElseGet(() -> newStats(record));
		apply(stats, record);
		priceStatsRepository.save(stats);
	}

	/**
	 * Folds one observation into {@code stats} (history, current/lowest/highest/median, promo flag, timestamps). The
	 * single place this is computed: {@link #record} and the batch path ({@link PriceBatchWriter}) both use it, so a
	 * batch yields exactly what the same observations applied one by one would.
	 */
	static void apply(PriceStats stats, PriceRecord record) {
		List<Map<String, Object>> history = new ArrayList<>(
				stats.getPriceHistory() != null ? stats.getPriceHistory() : List.of());
		history.add(observation(record));
		if (history.size() > MAX_HISTORY_SIZE) {
			history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY_SIZE, history.size()));
		}

		List<BigDecimal> sortedPrices = history.stream()
				.map(entry -> toBigDecimal(entry.get("price")))
				.sorted()
				.toList();

		stats.setChainId(record.getChainId());
		stats.setCurrency(record.getCurrency());
		stats.setPriceHistory(history);
		stats.setCurrentPrice(record.getPriceAmount());
		stats.setCurrentPromoFlag(record.isPromoFlag());
		stats.setLowestPrice(sortedPrices.get(0));
		stats.setHighestPrice(sortedPrices.get(sortedPrices.size() - 1));
		stats.setMedianPrice(median(sortedPrices));
		stats.setObservationCount(history.size());
		stats.setLastObservedAt(record.getCapturedAt());
		stats.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
	}

	/** Forgets the summaries of one item id (used when the product chosen for a list item is replaced). */
	@Transactional
	public void clearItem(String itemId) {
		priceStatsRepository.deleteByItemId(itemId);
	}

	static PriceStats newStats(PriceRecord record) {
		return PriceStats.builder()
				.itemId(record.getItemId())
				.storeId(record.getStoreId())
				.chainId(record.getChainId())
				.currency(record.getCurrency())
				.priceHistory(new ArrayList<>())
				.firstObservedAt(record.getCapturedAt())
				.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
				.build();
	}

	private static Map<String, Object> observation(PriceRecord record) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("price", record.getPriceAmount());
		entry.put("capturedAt", record.getCapturedAt().toString());
		return entry;
	}

	/**
	 * A freshly-built entry's "price" is still a BigDecimal, but one read back from the database
	 * has round-tripped through JSON deserialization and comes back as a Double - handle both.
	 */
	private static BigDecimal toBigDecimal(Object value) {
		return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
	}

	private static BigDecimal median(List<BigDecimal> sortedPrices) {
		int size = sortedPrices.size();
		if (size % 2 == 1) {
			return sortedPrices.get(size / 2);
		}
		BigDecimal lower = sortedPrices.get(size / 2 - 1);
		BigDecimal upper = sortedPrices.get(size / 2);
		return lower.add(upper).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
	}
}
