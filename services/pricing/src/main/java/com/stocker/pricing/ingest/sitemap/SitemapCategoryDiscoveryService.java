package com.stocker.pricing.ingest.sitemap;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.sitemap.model.SitemapCategoryCacheEntry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Two-tier (in-memory L1 + Postgres L2, see {@link SitemapCategoryCacheRepository}) cache of
 * each chain's sitemap-derived category-page URLs, so resolving "which category page has milk"
 * doesn't mean re-downloading and re-parsing a chain's full sitemap on every lookup. Entries are
 * aggressively cached: refreshed only after {@code app.ingest.sitemap-cache-ttl-days} (default 7)
 * has elapsed since the last successful fetch.
 */
public class SitemapCategoryDiscoveryService {

	private static final Logger log = LoggerFactory.getLogger(SitemapCategoryDiscoveryService.class);

	private static final Map<ChainId, String> SITEMAP_INDEX_URL = Map.of(
			ChainId.NEWWORLD, "https://www.newworld.co.nz/ecomsitemap_index.xml",
			ChainId.PAKNSAVE, "https://www.paknsave.co.nz/ecomsitemap_index.xml",
			ChainId.WOOLWORTHS, "https://www.woolworths.co.nz/sitemap.xml");

	private final SitemapXmlFetcher sitemapXmlFetcher;
	private final SitemapCategoryCacheRepository repository;
	private final Duration cacheTtl;
	private final Map<ChainId, CachedEntry> memoryCache = new ConcurrentHashMap<>();

	public SitemapCategoryDiscoveryService(WebClient webClient, SitemapCategoryCacheRepository repository,
			IngestProperties properties) {
		this.sitemapXmlFetcher = new SitemapXmlFetcher(webClient);
		this.repository = repository;
		this.cacheTtl = Duration.ofDays(properties.getSitemapCacheTtlDays());
	}

	/** Category-page URLs for this chain whose last path segment contains {@code keyword}. */
	public List<String> findCategoryUrls(ChainId chainId, String keyword) {
		return allCategoryUrls(chainId).stream()
				.filter(url -> CategoryUrlMatcher.matches(url, keyword))
				.toList();
	}

	/** All of this chain's cached (or freshly fetched) category-page URLs. */
	public List<String> allCategoryUrls(ChainId chainId) {
		CachedEntry cached = memoryCache.get(chainId);
		if (cached != null && !isStale(cached.fetchedAt())) {
			return cached.urls();
		}

		SitemapCategoryCacheEntry dbEntry = repository.findByChainId(chainId.name()).orElse(null);
		if (dbEntry != null && !isStale(dbEntry.getFetchedAt())) {
			memoryCache.put(chainId, new CachedEntry(dbEntry.getCategoryUrls(), dbEntry.getFetchedAt()));
			return dbEntry.getCategoryUrls();
		}

		List<String> fresh = fetchCategoryUrls(chainId);
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		memoryCache.put(chainId, new CachedEntry(fresh, now));
		persist(chainId, fresh, now, dbEntry);
		return fresh;
	}

	private List<String> fetchCategoryUrls(ChainId chainId) {
		String indexUrl = SITEMAP_INDEX_URL.get(chainId);
		List<String> sitemaps = sitemapXmlFetcher.fetchLocs(indexUrl);
		String categoriesSitemapUrl = sitemaps.stream()
				.filter(url -> url.toLowerCase(Locale.ROOT).contains("categories"))
				.findFirst()
				.orElse(null);
		if (categoriesSitemapUrl == null) {
			log.warn("{}: no 'categories' sitemap found in index {}", chainId, indexUrl);
			return List.of();
		}
		List<String> categoryUrls = sitemapXmlFetcher.fetchLocs(categoriesSitemapUrl);
		log.info("{}: fetched {} category URLs from {}", chainId, categoryUrls.size(), categoriesSitemapUrl);
		return categoryUrls;
	}

	private void persist(ChainId chainId, List<String> urls, OffsetDateTime fetchedAt, SitemapCategoryCacheEntry existing) {
		if (urls.isEmpty()) {
			return;
		}
		SitemapCategoryCacheEntry entry = existing != null ? existing : new SitemapCategoryCacheEntry();
		entry.setChainId(chainId.name());
		entry.setCategoryUrls(urls);
		entry.setFetchedAt(fetchedAt);
		entry.setUpdatedAt(fetchedAt);
		repository.save(entry);
	}

	private boolean isStale(OffsetDateTime fetchedAt) {
		return fetchedAt == null || Duration.between(fetchedAt, OffsetDateTime.now(ZoneOffset.UTC)).compareTo(cacheTtl) > 0;
	}

	private record CachedEntry(List<String> urls, OffsetDateTime fetchedAt) {
	}
}
