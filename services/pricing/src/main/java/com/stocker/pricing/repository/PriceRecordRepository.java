package com.stocker.pricing.repository;

import com.stocker.pricing.model.PriceRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceRecordRepository extends JpaRepository<PriceRecord, UUID> {

	List<PriceRecord> findByItemIdAndStoreId(String itemId, String storeId);
}
