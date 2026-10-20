package com.stocker.pricing.service.normalize;

import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.UnmatchedSearchTermRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
		String name = eligibleName(record);
		if (name == null) {
			return;
		}
		NormalizedProduct row = repository.findByItemIdAndStoreId(record.getItemId(), record.getStoreId())
				.orElseGet(() -> NormalizedProduct.builder().itemId(record.getItemId()).storeId(record.getStoreId()).build());
		if (apply(row, record, name)) {
			repository.save(row);
		}
	}

	/**
	 * {@link #observe} for a whole batch in a fixed number of round trips: one read of the existing rows, the records
	 * folded in list order in memory (a later record of the same product sees the earlier one, exactly as sequential
	 * calls would), one {@code saveAll}. Same rules per record, same resulting rows.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void observeAll(List<PriceRecord> records) {
		java.util.Set<String> itemIds = new java.util.HashSet<>();
		for (PriceRecord record : records) {
			if (eligibleName(record) != null) {
				itemIds.add(record.getItemId());
			}
		}
		if (itemIds.isEmpty()) {
			return;
		}
		java.util.Map<String, NormalizedProduct> rows = new java.util.LinkedHashMap<>();
		for (NormalizedProduct existing : repository.findByItemIdIn(itemIds)) {
			rows.put(existing.getItemId() + '\u0000' + existing.getStoreId(), existing);
		}
		java.util.Set<NormalizedProduct> touched = new java.util.LinkedHashSet<>();
		for (PriceRecord record : records) {
			String name = eligibleName(record);
			if (name == null) {
				continue;
			}
			NormalizedProduct row = rows.computeIfAbsent(record.getItemId() + '\u0000' + record.getStoreId(),
					k -> NormalizedProduct.builder().itemId(record.getItemId()).storeId(record.getStoreId()).build());
			if (apply(row, record, name)) {
				touched.add(row);
			}
		}
		repository.saveAll(touched);
	}

	/** The product name when the record is one {@link #observe} accepts (an ingested row with a name and a positive price), else null. */
	private static String eligibleName(PriceRecord record) {
		if (record.getItemId() == null || !record.getItemId().contains(":")) {
			return null;
		}
		String name = StoreProductText.name(record);
		if (!StringUtils.hasText(name) || record.getPriceAmount() == null || record.getPriceAmount().signum() <= 0) {
			return null;
		}
		return name;
	}

	/** Copies the record into the row unless the row already holds a newer observation; true if the row changed. */
	private boolean apply(NormalizedProduct row, PriceRecord record, String name) {
		if (row.getCapturedAt() != null && row.getCapturedAt().isAfter(record.getCapturedAt())) {
			return false;
		}
		Derived derived = derive(name, record.getPriceAmount());
		row.setChainId(record.getChainId());
		row.setCanonicalKey(derived.canonicalKey());
		row.setNormalizedName(derived.normalizedName());
		row.setDisplayName(name);
		row.setBrand(StoreProductText.realBrand(record));
		row.setPackQuantity(derived.packQuantity());
		row.setPackUnit(derived.packUnit());
		row.setPriceAmount(record.getPriceAmount());
		row.setUnitPrice(derived.unitPrice());
		row.setCurrency(record.getCurrency());
		row.setPromoFlag(record.isPromoFlag());
		row.setCapturedAt(record.getCapturedAt());
		row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
		return true;
	}

	/** Normalises up to {@code limit} price_records rows that predate normalized_products; returns how many it read. */
	public int backfill(int limit) {
		List<PriceRecord> batch = repository.findUnnormalizedRecords(limit);
		batch.forEach(this::observe);
		return batch.size();
	}

	/**
	 * Runs {@link #backfill} batch after batch until one comes back short, so the whole backlog is caught up and not
	 * just the first batch. {@code maxRounds} is a safety cap so a backlog that never shrinks still ends.
	 *
	 * @return how many rows were read in total
	 */
	public int backfillAll(int batchSize, int maxRounds) {
		int total = 0;
		for (int round = 0; round < maxRounds; round++) {
			int read = backfill(batchSize);
			total += read;
			if (read < batchSize) {
				break;
			}
		}
		return total;
	}

	/** The columns derived from a product's name and price; the one place {@link #observe} and {@link #rekeyAll} compute them. */
	private record Derived(String canonicalKey, String normalizedName, BigDecimal packQuantity, String packUnit,
			BigDecimal unitPrice) {
	}

	private Derived derive(String name, BigDecimal price) {
		PackSize size = ProductNormalizer.sizeOf(name);
		Optional<CommonProduct> common = catalog.match(name);
		return new Derived(common.map(CommonProduct::key).orElse(null), ProductNormalizer.normalizedName(name),
				size == null ? null : size.quantity(), size == null ? null : size.unit().code(),
				size == null ? null : size.unitPrice(price));
	}

	/** Outcome of {@link #rekeyAll}: rows read, rows whose derived columns changed, of those how many changed key, pages that failed. */
	public record RekeyResult(int read, int changed, int keyChanged, int failedPages) {
	}

	/**
	 * Recomputes the derived columns (canonical key, normalised name, pack size, unit price) of every stored row from
	 * the CURRENT catalog and normaliser, so rows keyed by an older catalog stop polluting results before the next
	 * scrape. Database only: it never touches price_records or price_stats, never deletes, and writes only rows whose
	 * values changed (a second run changes nothing). Brand is left alone: it is not derivable from the display name.
	 * Not transactional itself: each page is one read and one {@code saveAll} of the changed rows only.
	 */
	public RekeyResult rekeyAll(int batchSize) {
		int size = Math.max(1, batchSize);
		int read = 0;
		int changed = 0;
		int keyChanged = 0;
		int failedPages = 0;
		List<String> examples = new ArrayList<>();
		int pageNo = 0;
		boolean more = true;
		while (more) {
			Page<NormalizedProduct> page;
			try {
				page = repository.findAll(PageRequest.of(pageNo, size, Sort.by("id")));
			}
			catch (RuntimeException e) {
				failedPages++;
				log.warn("normalized_products re-key: could not read page {}, stopping: {}", pageNo, e.getMessage());
				break;
			}
			List<NormalizedProduct> dirty = new ArrayList<>();
			List<String> pageExamples = new ArrayList<>();
			int pageKeyChanged = 0;
			for (NormalizedProduct row : page.getContent()) {
				read++;
				try {
					String oldKey = row.getCanonicalKey();
					if (!rekey(row)) {
						continue;
					}
					dirty.add(row);
					if (!Objects.equals(oldKey, row.getCanonicalKey())) {
						pageKeyChanged++;
						if (examples.size() + pageExamples.size() < 20) {
							pageExamples.add("'" + row.getDisplayName() + "' " + oldKey + " -> " + row.getCanonicalKey());
						}
					}
				}
				catch (RuntimeException e) {
					log.debug("normalized_products re-key: skipping row {}: {}", row.getId(), e.getMessage());
				}
			}
			if (!dirty.isEmpty()) {
				try {
					repository.saveAll(dirty);
					changed += dirty.size();
					keyChanged += pageKeyChanged;
					examples.addAll(pageExamples);
				}
				catch (RuntimeException e) {
					failedPages++;
					log.warn("normalized_products re-key: page {} not saved ({} rows): {}", pageNo, dirty.size(), e.getMessage());
				}
			}
			more = page.hasNext();
			pageNo++;
		}
		RekeyResult result = new RekeyResult(read, changed, keyChanged, failedPages);
		log.info("normalized_products re-key: read {}, changed {}, key changed {}, failed pages {}. Examples: {}", read,
				changed, keyChanged, failedPages, examples);
		return result;
	}

	/** Applies the current derivation to {@code row}; true if any derived value differs (the row is then dirty). */
	private boolean rekey(NormalizedProduct row) {
		if (!StringUtils.hasText(row.getDisplayName()) || row.getPriceAmount() == null) {
			return false;
		}
		// Rows stored before pack counts were recognised kept "30pk" as the brand; fold it into the name so the size
		// survives (and so a second pass finds nothing to change).
		String display = row.getDisplayName();
		boolean foldBrand = StoreProductText.sizeLike(row.getBrand());
		if (foldBrand && !display.toLowerCase(java.util.Locale.ROOT).contains(row.getBrand().trim().toLowerCase(java.util.Locale.ROOT))) {
			display = display + " " + row.getBrand().trim();
		}
		Derived d = derive(display, row.getPriceAmount());
		boolean differs = foldBrand || !Objects.equals(row.getCanonicalKey(), d.canonicalKey())
				|| !Objects.equals(row.getNormalizedName(), d.normalizedName())
				|| !Objects.equals(row.getPackUnit(), d.packUnit())
				|| !sameNumber(row.getPackQuantity(), d.packQuantity())
				|| !sameNumber(row.getUnitPrice(), d.unitPrice());
		if (differs) {
			row.setDisplayName(display);
			if (foldBrand) {
				row.setBrand(null);
			}
			row.setCanonicalKey(d.canonicalKey());
			row.setNormalizedName(d.normalizedName());
			row.setPackQuantity(d.packQuantity());
			row.setPackUnit(d.packUnit());
			row.setUnitPrice(d.unitPrice());
			row.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
		}
		return differs;
	}

	private static boolean sameNumber(BigDecimal a, BigDecimal b) {
		return a == null || b == null ? a == b : a.compareTo(b) == 0;
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
