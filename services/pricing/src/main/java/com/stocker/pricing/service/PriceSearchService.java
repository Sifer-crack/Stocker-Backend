package com.stocker.pricing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.refresh.PriceRefreshRequest;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.cache.PriceCache;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * On-demand price search: cache-aside over {@code price_records}, with a bounded synchronous
 * fallback fetch and an async Kafka-driven refresh escalation for cache misses. See
 * services/pricing's plan doc / TASKS.md for the full design rationale.
 */
@Service
public class PriceSearchService {

	private static final Logger log = LoggerFactory.getLogger(PriceSearchService.class);

	private final WebFetcher webFetcher;
	private final PriceRecordRepository priceRecordRepository;
	private final PriceCache priceCache;
	private final PricingCacheProperties cacheProperties;
	private final EventPublisher eventPublisher;
	private final ExecutorService priceFallbackExecutor;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final String refreshTopic;

	private final PriceStatsService priceStatsService;

	public PriceSearchService(WebFetcher webFetcher, PriceRecordRepository priceRecordRepository,
			PriceCache priceCache, PricingCacheProperties cacheProperties, EventPublisher eventPublisher,
			ExecutorService priceFallbackExecutor, KafkaTemplate<String, String> kafkaTemplate,
			ObjectMapper objectMapper, @Value("${app.pricing.refresh.topic}") String refreshTopic,
			PriceStatsService priceStatsService) {
		this.webFetcher = webFetcher;
		this.priceRecordRepository = priceRecordRepository;
		this.priceCache = priceCache;
		this.cacheProperties = cacheProperties;
		this.eventPublisher = eventPublisher;
		this.priceFallbackExecutor = priceFallbackExecutor;
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.refreshTopic = refreshTopic;
		this.priceStatsService = priceStatsService;
	}

	/**
	 * On-demand entry point (gRPC Search). A cache hit (L1 in-process, else L2 price_records within
	 * the freshness window) returns immediately. A miss makes one bounded, tight-timeout attempt at
	 * a live fetch; on timeout/failure it returns best-effort stale/empty data and defers a real
	 * refresh to the async Kafka consumer rather than blocking the caller further.
	 */
	public List<PriceRecord> search(String searchTerm, String itemId, List<String> storeUrls, String category) {
		List<PriceRecord> cached = safeGetFresh(itemId);
		if (!cached.isEmpty()) {
			log.info("Cache hit for itemId={}", itemId);
			return cached;
		}

		Future<List<PriceRecord>> future =
				priceFallbackExecutor.submit(() -> refreshFromProvider(searchTerm, itemId, storeUrls, category));
		try {
			return future.get(cacheProperties.getFallback().getTimeout().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("Fallback fetch timed out for itemId={}, term={}; deferring to async refresh", itemId, searchTerm);
			publishRefreshRequest(searchTerm, itemId, storeUrls, category);
			return safeGetStale(itemId);
		} catch (Exception e) {
			log.error("Fallback fetch failed for itemId={}, term={}; deferring to async refresh", itemId, searchTerm, e);
			publishRefreshRequest(searchTerm, itemId, storeUrls, category);
			return safeGetStale(itemId);
		}
	}

	/**
	 * Fetches live from the active WebFetcher, persists, publishes PriceRecordCaptured, and warms
	 * the cache. Shared by the bounded on-demand fallback in {@link #search} and
	 * {@code PriceRefreshRequestConsumer}'s unbounded async retry — the only difference between the
	 * two callers is the timeout wrapped around this method, not the fetch/persist/publish logic.
	 */
	public List<PriceRecord> refreshFromProvider(String searchTerm, String itemId, List<String> storeUrls,
			String category) {
		WebFetchRequest request = WebFetchRequest.builder()
				.searchTerm(searchTerm)
				.storeUrls(storeUrls)
				.category(category)
				.build();
		List<RawProduct> products = webFetcher.fetch(request);
		List<PriceRecord> saved = products.stream()
				.map(product -> RawProductPriceRecordMapper.toPriceRecord(itemId, product))
				.map(priceRecordRepository::save)
				.toList();
		saved.forEach(eventPublisher::publishPriceRecordCaptured);
		saved.forEach(priceStatsService::recordObservation);
		priceCache.put(itemId, saved);
		log.info("Refreshed {} price records for itemId={}, term={}", saved.size(), itemId, searchTerm);
		return saved;
	}

	private void publishRefreshRequest(String searchTerm, String itemId, List<String> storeUrls, String category) {
		try {
			String payload =
					objectMapper.writeValueAsString(new PriceRefreshRequest(itemId, searchTerm, storeUrls, category));
			kafkaTemplate.send(refreshTopic, itemId, payload);
		} catch (Exception e) {
			log.error("Failed to publish refresh request for itemId={}", itemId, e);
		}
	}

	private List<PriceRecord> safeGetFresh(String itemId) {
		try {
			return priceCache.getFresh(itemId);
		} catch (Exception e) {
			log.error("Cache lookup failed for itemId={}", itemId, e);
			return List.of();
		}
	}

	private List<PriceRecord> safeGetStale(String itemId) {
		try {
			return priceCache.getStale(itemId);
		} catch (Exception e) {
			log.error("Stale-fallback lookup failed for itemId={}", itemId, e);
			return List.of();
		}
	}
}
