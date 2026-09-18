package com.stocker.pricing.repository;

import com.stocker.pricing.model.PriceStats;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceStatsRepository extends JpaRepository<PriceStats, UUID> {

	Optional<PriceStats> findByItemIdAndStoreId(String itemId, String storeId);

	List<PriceStats> findByItemId(String itemId);
}
