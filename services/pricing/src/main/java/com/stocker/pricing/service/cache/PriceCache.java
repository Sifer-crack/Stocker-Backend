package com.stocker.pricing.service.cache;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Two-tier cache-aside for on-demand price lookups, keyed by the caller-supplied itemId that
 * groups one search's results across chains (see PriceSearchService). L1 is in-process, L2 is
 * price_records itself via a freshness-window read — same shape as
 * ingest/sitemap/SitemapCategoryDiscoveryService's L1/L2 cache.
 *
 * Strictly cache-aside: a future Redis layer can sit in front of L2 without changing this
 * contract, as long as it fails open (miss/down -&gt; fall through to L2 exactly as if Redis
 * were absent). Not built yet — see TASKS.md.
 */
@Component
public class PriceCache {

	private final PriceRecordRepository repository;
	private final PricingCacheProperties properties;
	private final Map<String, CachedEntry> memoryCache = new ConcurrentHashMap<>();

	public PriceCache(PriceRecordRepository repository, PricingCacheProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/** Fresh, cached price records for this itemId, or empty if both L1 and L2 are stale/missing. */
	public List<PriceRecord> getFresh(String itemId) {
		CachedEntry cached = memoryCache.get(itemId);
		if (cached != null && !isL1Stale(cached.cachedAt())) {
			return cached.records();
		}

		List<PriceRecord> fresh = repository.findByItemIdAndCapturedAtAfter(itemId, freshnessThreshold());
		if (!fresh.isEmpty()) {
			memoryCache.put(itemId, new CachedEntry(fresh, OffsetDateTime.now(ZoneOffset.UTC)));
		}
		return fresh;
	}

	/** Best-effort last-known records for this itemId regardless of freshness. */
	public List<PriceRecord> getStale(String itemId) {
		CachedEntry cached = memoryCache.get(itemId);
		if (cached != null) {
			return cached.records();
		}
		return repository.findByItemId(itemId);
	}

	/** Warms both tiers after a live fetch/refresh. */
	public void put(String itemId, List<PriceRecord> records) {
		if (records.isEmpty()) {
			return;
		}
		memoryCache.put(itemId, new CachedEntry(records, OffsetDateTime.now(ZoneOffset.UTC)));
	}

	private OffsetDateTime freshnessThreshold() {
		return OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getCache().getFreshnessWindow());
	}

	private boolean isL1Stale(OffsetDateTime cachedAt) {
		return Duration.between(cachedAt, OffsetDateTime.now(ZoneOffset.UTC))
				.compareTo(properties.getCache().getL1Ttl()) > 0;
	}

	private record CachedEntry(List<PriceRecord> records, OffsetDateTime cachedAt) {
	}
}
