package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PriceFetcherService {

	private static final Logger log = LoggerFactory.getLogger(PriceFetcherService.class);

	private final PriceRecordRepository priceRecordRepository;
	private final PriceStatsService priceStatsService;

	public List<PriceRecord> fetch(String itemId, String storeId) {
		log.info("Fetching price records for itemId={}, storeId={}", itemId, storeId);
		return priceRecordRepository.findByItemIdAndStoreId(itemId, storeId);
	}

	public PriceRecord save(PriceRecord record) {
		log.info("Saving price record for itemId={}, storeId={}", record.getItemId(), record.getStoreId());
		PriceRecord saved = priceRecordRepository.save(record);
		priceStatsService.recordObservation(saved);
		return saved;
	}
}
