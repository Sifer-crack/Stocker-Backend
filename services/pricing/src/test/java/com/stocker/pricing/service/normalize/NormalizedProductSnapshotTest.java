package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Price lookups are answered from memory: the normalised products are loaded in one query and reused, instead of
 * one or more database round trips per lookup (about 190 ms each from New Zealand to a US Neon region).
 */
class NormalizedProductSnapshotTest {

	private static final Instant START = Instant.parse("2026-10-19T03:00:00Z");
	private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(START, ZoneOffset.UTC);

	private NormalizedProductRepository repository;
	private PricingCacheProperties properties;
	private MutableClock clock;
	private NormalizedProductSnapshot snapshot;

	private static final class MutableClock extends Clock {
		Instant now = START;

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private static NormalizedProduct row(String chain, String key, String name, OffsetDateTime at) {
		return NormalizedProduct.builder().itemId(chain + ":" + name).storeId(chain + ":").chainId(chain).canonicalKey(key)
				.normalizedName(name).displayName(name).priceAmount(new BigDecimal("3.00")).currency("NZD").capturedAt(at).build();
	}

	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		properties = new PricingCacheProperties();
		properties.getSnapshot().setTtl(Duration.ofSeconds(30));
		clock = new MutableClock();
		snapshot = new NormalizedProductSnapshot(repository, properties, clock);
		when(repository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("paknsave", "milk", "milk 2l", NOW.minusHours(2)),
				row("newworld", "milk", "milk 2l", NOW.minusHours(5)),
				row("newworld", "milk", "milk 1l", NOW.minusHours(1)),
				row("paknsave", "eggs", "eggs 12", NOW.minusHours(3)),
				row("paknsave", null, "wine", NOW.minusDays(40))));
	}

	@Test
	void manyLookupsCostOneDatabaseQuery() {
		for (int i = 0; i < 50; i++) {
			snapshot.findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusDays(3));
			snapshot.findByCapturedAtAfter(NOW.minusDays(3));
			snapshot.newestByChain("eggs");
		}

		verify(repository, times(1)).findByCapturedAtAfter(any());
		verifyNoMoreInteractions(repository);
	}

	@Test
	void aLookupByKeyReturnsOnlyThatProductAndAppliesTheDateWindow() {
		assertThat(snapshot.findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusDays(3))).hasSize(3)
				.allSatisfy(product -> assertThat(product.getCanonicalKey()).isEqualTo("milk"));
		assertThat(snapshot.findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusHours(4))).hasSize(2);   // drops the 5h-old row
		assertThat(snapshot.findByCanonicalKeyAndCapturedAtAfter("bread", NOW.minusDays(3))).isEmpty();
	}

	@Test
	void anyProductLookupAppliesTheDateWindowToo() {
		assertThat(snapshot.findByCapturedAtAfter(NOW.minusDays(3))).hasSize(4);       // the 40-day-old wine is out
		assertThat(snapshot.findByCapturedAtAfter(NOW.minusDays(60))).hasSize(5);
	}

	@Test
	void newestPerChainIsDerivedWithoutAnotherQuery() {
		List<Object[]> newest = snapshot.newestByChain("milk");

		assertThat(newest).hasSize(2);
		assertThat(newest).anySatisfy(row -> {
			assertThat(row[0]).isEqualTo("paknsave");
			assertThat(row[1]).isEqualTo(NOW.minusHours(2));
		});
		assertThat(newest).anySatisfy(row -> {
			assertThat(row[0]).isEqualTo("newworld");
			assertThat(row[1]).isEqualTo(NOW.minusHours(1));   // the newer of the two newworld milks
		});
		verify(repository, times(1)).findByCapturedAtAfter(any());
	}

	@Test
	void itReloadsOnceTheTtlHasPassed() {
		snapshot.newestByChain("milk");
		clock.now = START.plusSeconds(31);

		snapshot.newestByChain("milk");

		verify(repository, times(2)).findByCapturedAtAfter(any());
	}

	@Test
	void aFailedReloadKeepsServingTheLastGoodData() {
		snapshot.newestByChain("milk");
		clock.now = START.plusSeconds(31);
		when(repository.findByCapturedAtAfter(any())).thenThrow(new IllegalStateException("neon is down"));

		assertThat(snapshot.findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusDays(3))).hasSize(3);   // stale, not an error
	}

	@Test
	void aFailedFirstLoadIsAnError() {
		when(repository.findByCapturedAtAfter(any())).thenThrow(new IllegalStateException("neon is down"));

		assertThatThrownBy(() -> snapshot.newestByChain("milk")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void theScheduledRefreshWarmsTheSnapshotAndSwallowsFailures() {
		snapshot.refresh();
		snapshot.findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusDays(3));
		verify(repository, times(1)).findByCapturedAtAfter(any());

		when(repository.findByCapturedAtAfter(any())).thenThrow(new IllegalStateException("neon is down"));
		snapshot.refresh();   // must not throw
	}

	@Test
	void aZeroTtlPassesStraightThroughToTheRepository() {
		properties.getSnapshot().setTtl(Duration.ZERO);
		when(repository.findByCanonicalKeyAndCapturedAtAfter(any(), any())).thenReturn(List.of());
		when(repository.newestByChain(any())).thenReturn(List.of());

		snapshot.findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusDays(3));
		snapshot.newestByChain("milk");
		snapshot.findByCapturedAtAfter(NOW.minusDays(3));

		verify(repository).findByCanonicalKeyAndCapturedAtAfter("milk", NOW.minusDays(3));
		verify(repository).newestByChain("milk");
		verify(repository, times(1)).findByCapturedAtAfter(NOW.minusDays(3));
	}
}
