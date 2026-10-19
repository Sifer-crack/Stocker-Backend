package com.stocker.pricing.service.normalize;

import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.UnmatchedSearchTermRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Keeps normalized_products in step with price_records. {@link #observe} is called from
 * PriceStatsService.recordObservation (so every price_records write is covered) and only ever
 * touches the database: normalising never triggers a request to a supermarket.
 */
@Service
public class NormalizedProductService {

	private static final Logger log = LoggerFactory.getLogger(NormalizedProductService.class);

	private final NormalizedProductRepository repository;
	private final UnmatchedSearchTermRepository unmatchedTerms;
	private final CommonProductCatalog catalog;

	public NormalizedProductService(NormalizedProductRepository repository,
			UnmatchedSearchTermRepository unmatchedTerms, CommonProductCatalog catalog) {
		this.repository = repository;
		this.unmatchedTerms = unmatchedTerms;
		this.catalog = catalog;
	}

	/** Rows stored under a caller's item id (a UUID, no colon) are copies of ingested rows and are skipped. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void observe(PriceRecord record) {
		if (record.getItemId() == null || !record.getItemId().contains(":")) {
			return;
		}
		String name = StoreProductText.name(record);
		if (!StringUtils.hasText(name) || record.getPriceAmount() == null || record.getPriceAmount().signum() <= 0) {
			return;
		}
		NormalizedProduct row = repository.findByItemIdAndStoreId(record.getItemId(), record.getStoreId())
				.orElseGet(() -> NormalizedProduct.builder().itemId(record.getItemId()).storeId(record.getStoreId()).build());
		if (row.getCapturedAt() != null && row.getCapturedAt().isAfter(record.getCapturedAt())) {
			return;
		}
		PackSize size = ProductNormalizer.sizeOf(name);
		Optional<CommonProduct> common = catalog.match(name);
		row.setChainId(record.getChainId());
		row.setCanonicalKey(common.map(CommonProduct::key).orElse(null));
		row.setNormalizedName(ProductNormalizer.normalizedName(name));
		row.setDisplayName(name);
		row.setBrand(StoreProductText.realBrand(record));
		row.setPackQuantity(size == null ? null : size.quantity());
		row.setPackUnit(size == null ? null : size.unit().code());
		row.setPriceAmount(record.getPriceAmount());
		row.setUnitPrice(size == null ? null : size.unitPrice(record.getPriceAmount()));
		row.setCurrency(record.getCurrency());
		row.setPromoFlag(record.isPromoFlag());
		row.setCapturedAt(record.getCapturedAt());
		row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
		repository.save(row);
	}

	/** Normalises up to {@code limit} price_records rows that predate normalized_products; returns how many it read. */
	public int backfill(int limit) {
		List<PriceRecord> batch = repository.findUnnormalizedRecords(limit);
		batch.forEach(this::observe);
		return batch.size();
	}

	@Transactional
	public void recordUnmatched(String term) {
		String normalized = ProductNormalizer.normalizedName(term);
		if (normalized.isBlank()) {
			return;
		}
		try {
			unmatchedTerms.record(normalized);
		} catch (RuntimeException e) {
			log.warn("Could not record unmatched search term '{}': {}", normalized, e.getMessage());
		}
	}
}
