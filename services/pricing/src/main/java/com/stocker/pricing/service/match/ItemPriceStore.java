package com.stocker.pricing.service.match;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stores normalised product rows under a shopping-list item id through the usual write path (price_records,
 * PriceRecordCaptured, price_stats), so that {@code CompareShoppingList} for that item prices exactly those
 * products. An observation already stored for the item (same chain, store and capture time) is not stored twice.
 * Shared by {@link ItemMatchService} (old /match) and the product search (/products).
 */
@Component
public class ItemPriceStore {

	private final PriceRecordRepository repository;
	private final PriceStatsService priceStatsService;
	private final EventPublisher eventPublisher;

	public ItemPriceStore(PriceRecordRepository repository, PriceStatsService priceStatsService,
			EventPublisher eventPublisher) {
		this.repository = repository;
		this.priceStatsService = priceStatsService;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Makes these rows THE stored price of the item: what was stored under the id for another product is dropped first
	 * (price_stats keeps the newest observation per store, so an earlier product observed more recently would otherwise
	 * keep winning in compare). Nothing changes when the item already holds exactly these observations.
	 */
	public void replace(String itemId, List<NormalizedProduct> sources) {
		List<PriceRecord> existing = repository.findByItemId(itemId);
		Set<String> wanted = new HashSet<>();
		sources.forEach(source -> wanted.add(storedKey(source.getChainId(), source.getStoreId(), source.getCapturedAt())));
		Set<String> held = new HashSet<>();
		existing.forEach(row -> held.add(storedKey(row)));
		if (!existing.isEmpty() && !held.equals(wanted)) {
			repository.deleteByItemId(itemId);
			priceStatsService.clearItem(itemId);
		}
		store(itemId, sources);
	}

	public void store(String itemId, List<NormalizedProduct> sources) {
		Set<String> alreadyStored = new HashSet<>();
		repository.findByItemId(itemId).forEach(row -> alreadyStored.add(storedKey(row)));
		for (NormalizedProduct source : sources) {
			if (!alreadyStored.add(storedKey(source.getChainId(), source.getStoreId(), source.getCapturedAt()))) {
				continue;
			}
			Map<String, Object> attributes = new LinkedHashMap<>();
			attributes.put("name", source.getDisplayName());
			if (StringUtils.hasText(source.getBrand())) {
				attributes.put("brand", source.getBrand());
			}
			PriceRecord saved = repository.save(PriceRecord.builder()
					.itemId(itemId)
					.storeId(source.getStoreId())
					.chainId(source.getChainId())
					.channel("pickup")
					.priceAmount(source.getPriceAmount())
					.currency(source.getCurrency())
					.promoFlag(source.isPromoFlag())
					.capturedAt(source.getCapturedAt())
					.rawAttributes(attributes)
					.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
					.build());
			eventPublisher.publishPriceRecordCaptured(saved);
			priceStatsService.recordObservation(saved);
		}
	}

	private static String storedKey(PriceRecord row) {
		return storedKey(row.getChainId(), row.getStoreId(), row.getCapturedAt());
	}

	private static String storedKey(String chainId, String storeId, OffsetDateTime capturedAt) {
		return chainId + "|" + storeId + "|" + (capturedAt == null ? "" : capturedAt.toInstant());
	}
}
