package com.stocker.pricing.repository;

import com.stocker.pricing.model.NormalizedProduct;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NormalizedProductRepository extends JpaRepository<NormalizedProduct, UUID> {

	Optional<NormalizedProduct> findByItemIdAndStoreId(String itemId, String storeId);

	List<NormalizedProduct> findByItemIdIn(java.util.Collection<String> itemIds);

	List<NormalizedProduct> findByCanonicalKeyAndCapturedAtAfter(String canonicalKey, OffsetDateTime since);

	List<NormalizedProduct> findByCapturedAtAfter(OffsetDateTime since);

	/**
	 * Ingested price_records rows that have no normalized_products row yet (backfill). Only rows the normaliser will
	 * accept (a name and a positive price): others never get a row, so selecting them would return the same rows on
	 * every run and crowd out the real backlog.
	 */
	@Query(value = """
			select p.* from price_records p
			where p.item_id like '%:%'
			  and nullif(btrim(p.raw_attributes->>'name'), '') is not null
			  and p.price_amount > 0
			  and not exists (select 1 from normalized_products n where n.item_id = p.item_id and n.store_id = p.store_id)
			order by p.captured_at desc
			limit :batch
			""", nativeQuery = true)
	List<com.stocker.pricing.model.PriceRecord> findUnnormalizedRecords(@Param("batch") int batch);

	/** Newest observation per chain for one common product: [chainId, capturedAt]. */
	@Query("select n.chainId, max(n.capturedAt) from NormalizedProduct n where n.canonicalKey = :key group by n.chainId")
	List<Object[]> newestByChain(@Param("key") String key);
}
