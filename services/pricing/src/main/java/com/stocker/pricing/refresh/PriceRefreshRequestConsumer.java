package com.stocker.pricing.refresh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.service.PriceSearchService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes PriceRefreshRequest messages published by PriceSearchService when an on-demand
 * cache-miss's synchronous fallback times out or fails. Retries the same fetch with no tight
 * timeout — unlike the on-demand path, this runs off any user-facing request, so it can afford to
 * wait on a slow provider. The *next* search for this item benefits from the refresh, not the one
 * that triggered it.
 *
 * No dedup of duplicate in-flight requests for the same itemId yet — harmless (idempotent
 * re-fetch), just occasionally wasteful; a follow-up if refresh volume warrants it.
 */
@Component
@RequiredArgsConstructor
public class PriceRefreshRequestConsumer {

	private static final Logger log = LoggerFactory.getLogger(PriceRefreshRequestConsumer.class);

	private final PriceSearchService priceSearchService;
	private final ObjectMapper objectMapper;

	@KafkaListener(topics = "${app.pricing.refresh.topic}", groupId = "${spring.kafka.consumer.group-id}")
	public void onRefreshRequested(String payload) {
		try {
			PriceRefreshRequest request = objectMapper.readValue(payload, PriceRefreshRequest.class);
			log.info("Processing async price refresh for itemId={}, term={}", request.itemId(), request.searchTerm());
			priceSearchService.refreshFromProvider(
					request.searchTerm(), request.itemId(), request.storeUrls(), request.category());
		} catch (Exception e) {
			log.error("Failed to process price refresh request: {}", payload, e);
		}
	}
}
