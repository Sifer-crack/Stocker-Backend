package com.stocker.pricing.repository;

import com.stocker.pricing.model.PriceRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRecordRepository extends JpaRepository<PriceRecord, UUID> {

	List<PriceRecord> findByItemIdAndStoreId(String itemId, String storeId);

	List<PriceRecord> findByItemId(String itemId);

	/** Cache-aside L2 read: rows for this itemId still within the configured freshness window. */
	List<PriceRecord> findByItemIdAndCapturedAtAfter(String itemId, OffsetDateTime threshold);

	/**
	 * Rows written by the scheduled ingest, whose item ids look like "chain:code" (a caller-supplied
	 * itemId is a UUID and never contains a colon), captured since the threshold.
	 */
	@Query("select p from PriceRecord p where p.itemId like '%:%' and p.capturedAt >= :since")
	List<PriceRecord> findIngestedSince(@Param("since") OffsetDateTime since);
}
