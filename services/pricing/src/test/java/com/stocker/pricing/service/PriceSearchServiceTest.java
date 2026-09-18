package com.stocker.pricing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.cache.PriceCache;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceSearchServiceTest {

	private static final String REFRESH_TOPIC = "stocker.pricing.refresh-requests.v1";

	private WebFetcher webFetcher;
	private PriceRecordRepository repository;
	private PriceCache priceCache;
	private EventPublisher eventPublisher;
	private KafkaTemplate<String, String> kafkaTemplate;
	private ExecutorService executor;
	private PricingCacheProperties cacheProperties;
	private PriceStatsService priceStatsService;
	private PriceSearchService service;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		webFetcher = mock(WebFetcher.class);
		repository = mock(PriceRecordRepository.class);
		priceCache = mock(PriceCache.class);
		eventPublisher = mock(EventPublisher.class);
		kafkaTemplate = mock(KafkaTemplate.class);
		executor = Executors.newSingleThreadExecutor();
		cacheProperties = new PricingCacheProperties();
		priceStatsService = mock(PriceStatsService.class);
		when(priceCache.getFresh(any())).thenReturn(List.of());
		when(priceCache.getStale(any())).thenReturn(List.of());
		service = new PriceSearchService(webFetcher, repository, priceCache, cacheProperties, eventPublisher,
				executor, kafkaTemplate, new ObjectMapper(), REFRESH_TOPIC, priceStatsService);
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	@Test
	void cacheHitReturnsWithoutFetching() {
		PriceRecord cachedRecord = PriceRecord.builder().itemId("item-1").build();
		when(priceCache.getFresh("item-1")).thenReturn(List.of(cachedRecord));

		List<PriceRecord> result = service.search("rice", "item-1", List.of(), null);

		assertEquals(1, result.size());
		verify(webFetcher, never()).fetch(any());
	}

	@Test
	void cacheMissFetchesMapsSavesAndPublishes() {
		RawProduct product = RawProduct.builder()
				.name("Rice 5kg")
				.price(new BigDecimal("12.90"))
				.currency("NZD")
				.storeUrl("https://www.limchhour.co.nz/rice")
				.chainId("limchhour")
				.build();
		when(webFetcher.fetch(any(WebFetchRequest.class))).thenReturn(List.of(product, product));
		PriceRecord saved = PriceRecord.builder().itemId("item-1").build();
		when(repository.save(any(PriceRecord.class))).thenReturn(saved);

		List<PriceRecord> result = service.search("rice", "item-1", List.of("https://www.limchhour.co.nz"), "grocery");

		assertEquals(2, result.size());
		ArgumentCaptor<WebFetchRequest> requestCaptor = ArgumentCaptor.forClass(WebFetchRequest.class);
		verify(webFetcher).fetch(requestCaptor.capture());
		assertEquals("rice", requestCaptor.getValue().getSearchTerm());
		assertEquals(List.of("https://www.limchhour.co.nz"), requestCaptor.getValue().getStoreUrls());
		assertEquals("grocery", requestCaptor.getValue().getCategory());
		ArgumentCaptor<PriceRecord> recordCaptor = ArgumentCaptor.forClass(PriceRecord.class);
		verify(repository, times(2)).save(recordCaptor.capture());
		PriceRecord mapped = recordCaptor.getAllValues().get(0);
		assertEquals("item-1", mapped.getItemId());
		assertEquals("https://www.limchhour.co.nz/rice", mapped.getStoreId());
		assertEquals("limchhour", mapped.getChainId());
		assertEquals(new BigDecimal("12.90"), mapped.getPriceAmount());
		verify(eventPublisher, times(2)).publishPriceRecordCaptured(any());
		verify(priceStatsService, times(2)).recordObservation(any());
		verify(priceCache).put(eq("item-1"), any());
	}

	@Test
	void returnsStaleDataAndPublishesRefreshRequestWhenFetcherThrows() {
		when(webFetcher.fetch(any(WebFetchRequest.class)))
				.thenThrow(new IllegalStateException("provider down"));

		List<PriceRecord> result = service.search("rice", "item-1", List.of(), null);

		assertTrue(result.isEmpty());
		verify(repository, never()).save(any(PriceRecord.class));
		verify(kafkaTemplate).send(eq(REFRESH_TOPIC), eq("item-1"), any());
	}

	@Test
	void fallbackTimeoutReturnsStaleDataAndPublishesRefreshRequest() throws InterruptedException {
		cacheProperties.getFallback().setTimeout(Duration.ofMillis(50));
		when(webFetcher.fetch(any(WebFetchRequest.class))).thenAnswer(invocation -> {
			Thread.sleep(500);
			return List.of();
		});
		PriceRecord staleRecord = PriceRecord.builder().itemId("item-1").build();
		when(priceCache.getStale("item-1")).thenReturn(List.of(staleRecord));

		List<PriceRecord> result = service.search("rice", "item-1", List.of(), null);

		assertEquals(1, result.size());
		verify(kafkaTemplate).send(eq(REFRESH_TOPIC), eq("item-1"), any());
	}
}
