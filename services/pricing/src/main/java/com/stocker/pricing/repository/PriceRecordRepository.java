package com.stocker.pricing.repository;

import com.stocker.pricing.model.PriceRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRecordRepository extends JpaRepository<PriceRecord, UUID> {

	List<PriceRecord> findByItemIdAndStoreId(String itemId, String storeId);

	List<PriceRecord> findByItemId(String itemId);

	/** Cache-aside L2 read: rows for this itemId still within the configured freshness window. */
	List<PriceRecord> findByItemIdAndCapturedAtAfter(String itemId, OffsetDateTime threshold);
}
