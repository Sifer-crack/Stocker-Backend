package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains price_stats (current/lowest/highest/median + a JSONB history) as a derived summary
 * of price_records - called alongside every price_records write (ingest batch persist, on-demand
 * search persist, and the gRPC Save RPC), never in place of it. price_records stays the raw,
 * append-only source of truth.
 *
 * <p>The same call then refreshes normalized_products (the normalised product index behind the price map). That
 * runs after the stats transaction has finished, so one write never holds two pooled database connections at
 * once, and a failure there is logged and never fails the write.
 */
@Service
@RequiredArgsConstructor
public class PriceStatsService {

	private static final Logger log = LoggerFactory.getLogger(PriceStatsService.class);

	private final PriceStatsWriter priceStatsWriter;
	private final PriceBatchWriter priceBatchWriter;
	private final NormalizedProductService normalizedProductService;

	public void recordObservation(PriceRecord record) {
		priceStatsWriter.record(record);
		try {
			normalizedProductService.observe(record);
		} catch (RuntimeException e) {
			log.warn("normalized_products not updated for item {}: {}", record.getItemId(), e.getMessage());
		}
	}

	/**
	 * Stores a batch of rows with a small, fixed number of database round trips: one transaction that appends all the
	 * price_records rows and brings price_stats up to date (observations applied in list order), then one pass over
	 * normalized_products. All-or-nothing for price_records + price_stats: if it throws, none of the batch was
	 * written, so the caller may retry row by row without duplicating anything. Same invariant as
	 * {@link #recordObservation}: no price_records write without its stats update.
	 */
	public void saveAndRecordAll(List<PriceRecord> records) {
		if (records.isEmpty()) {
			return;
		}
		priceBatchWriter.saveAndRecord(records);
		try {
			normalizedProductService.observeAll(records);
		} catch (RuntimeException e) {
			log.warn("normalized_products not updated for a batch of {} rows: {}", records.size(), e.getMessage());
		}
	}

	public void clearItem(String itemId) {
		priceStatsWriter.clearItem(itemId);
	}
}
