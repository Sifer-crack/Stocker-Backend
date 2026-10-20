package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.repository.PriceStatsRepository;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The batch counterpart of {@link PriceStatsWriter}: one transaction that appends a list of price_records rows and
 * updates their price_stats rows. Round trips are fixed, not per row: one {@code findByItemIdIn} for every affected
 * stats row, then the folds happen in memory (in list order, so several observations of one product behave exactly as
 * if applied one by one), then {@code saveAll} for both tables (JDBC-batched by Hibernate, see
 * {@code spring.jpa.properties.hibernate.jdbc.batch_size}).
 */
@Service
@RequiredArgsConstructor
public class PriceBatchWriter {

	private final PriceRecordRepository priceRecordRepository;
	private final PriceStatsRepository priceStatsRepository;

	@Transactional
	public void saveAndRecord(List<PriceRecord> records) {
		Set<String> itemIds = new HashSet<>();
		for (PriceRecord record : records) {
			itemIds.add(record.getItemId());
		}
		Map<String, PriceStats> byKey = new LinkedHashMap<>();
		for (PriceStats existing : priceStatsRepository.findByItemIdIn(itemIds)) {
			byKey.put(key(existing.getItemId(), existing.getStoreId()), existing);
		}
		for (PriceRecord record : records) {
			PriceStats stats = byKey.computeIfAbsent(key(record.getItemId(), record.getStoreId()),
					k -> PriceStatsWriter.newStats(record));
			PriceStatsWriter.apply(stats, record);
		}
		priceRecordRepository.saveAll(records);
		priceStatsRepository.saveAll(byKey.values());
	}

	private static String key(String itemId, String storeId) {
		return itemId + '\u0000' + storeId;
	}
}
