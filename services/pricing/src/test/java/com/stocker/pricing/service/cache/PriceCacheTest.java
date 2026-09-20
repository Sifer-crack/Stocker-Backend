package com.stocker.pricing.service.cache;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceCacheTest {

	private PriceRecordRepository repository;
	private PricingCacheProperties properties;
	private PriceCache cache;

	@BeforeEach
	void setUp() {
		repository = mock(PriceRecordRepository.class);
		properties = new PricingCacheProperties();
		cache = new PriceCache(repository, properties);
	}

	@Test
	void getFreshReturnsEmptyWhenBothTiersMiss() {
		when(repository.findByItemIdAndCapturedAtAfter(anyString(), any())).thenReturn(List.of());

		assertTrue(cache.getFresh("item-1").isEmpty());
	}

	@Test
	void getFreshPopulatesL1FromL2AndSubsequentCallSkipsRepository() {
		PriceRecord record = PriceRecord.builder().itemId("item-1").build();
		when(repository.findByItemIdAndCapturedAtAfter(anyString(), any())).thenReturn(List.of(record));

		List<PriceRecord> first = cache.getFresh("item-1");
		List<PriceRecord> second = cache.getFresh("item-1");

		assertEquals(1, first.size());
		assertEquals(1, second.size());
		verify(repository, times(1)).findByItemIdAndCapturedAtAfter(anyString(), any());
	}

	@Test
	void putWarmsL1SoGetFreshSkipsRepositoryEntirely() {
		PriceRecord record = PriceRecord.builder().itemId("item-1").build();

		cache.put("item-1", List.of(record));
		List<PriceRecord> result = cache.getFresh("item-1");

		assertEquals(1, result.size());
		verify(repository, never()).findByItemIdAndCapturedAtAfter(anyString(), any());
	}

	@Test
	void l1EntryExpiresAfterTtlAndFallsThroughToL2() {
		properties.getCache().setL1Ttl(Duration.ofMillis(1));
		PriceRecord record = PriceRecord.builder().itemId("item-1").build();
		cache.put("item-1", List.of(record));
		when(repository.findByItemIdAndCapturedAtAfter(anyString(), any())).thenReturn(List.of(record));

		await(5);
		cache.getFresh("item-1");

		verify(repository).findByItemIdAndCapturedAtAfter(anyString(), any());
	}

	@Test
	void getStaleFallsBackToRepositoryWhenNotCached() {
		PriceRecord record = PriceRecord.builder().itemId("item-1").build();
		when(repository.findByItemId("item-1")).thenReturn(List.of(record));

		List<PriceRecord> result = cache.getStale("item-1");

		assertEquals(1, result.size());
	}

	private static void await(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
