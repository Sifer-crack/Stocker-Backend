package com.stocker.pricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.model.PriceStats;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.repository.PriceStatsRepository;
import com.stocker.pricing.repository.UnmatchedSearchTermRepository;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The batch path must leave exactly the data the one-by-one path leaves, in a number of repository calls that does not
 * grow with the number of rows. In-memory repositories stand in for the database and count every call (each call is
 * one round trip, about 190 ms to Neon).
 */
class PriceBatchWriterTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);

	/** In-memory repositories; one instance is one "database". */
	private static final class Db {
		final Map<String, PriceStats> stats = new LinkedHashMap<>();
		final Map<String, NormalizedProduct> normalized = new LinkedHashMap<>();
		final List<PriceRecord> records = new ArrayList<>();
		final PriceStatsRepository statsRepo = mock(PriceStatsRepository.class);
		final PriceRecordRepository recordRepo = mock(PriceRecordRepository.class);
		final NormalizedProductRepository normalizedRepo = mock(NormalizedProductRepository.class);

		Db() {
			when(statsRepo.findByItemIdAndStoreId(anyString(), anyString()))
					.thenAnswer(i -> Optional.ofNullable(stats.get(i.getArgument(0) + "|" + i.getArgument(1))));
			when(statsRepo.findByItemIdIn(anyCollection())).thenAnswer(i -> {
				java.util.Collection<String> ids = i.getArgument(0);
				return stats.values().stream().filter(s -> ids.contains(s.getItemId())).toList();
			});
			when(statsRepo.save(any(PriceStats.class))).thenAnswer(i -> putStats(i.getArgument(0)));
			when(statsRepo.saveAll(anyIterable())).thenAnswer(i -> {
				List<PriceStats> out = new ArrayList<>();
				for (PriceStats s : (Iterable<PriceStats>) i.getArgument(0)) {
					out.add(putStats(s));
				}
				return out;
			});
			when(recordRepo.save(any(PriceRecord.class))).thenAnswer(i -> {
				records.add(i.getArgument(0));
				return i.getArgument(0);
			});
			when(recordRepo.saveAll(anyIterable())).thenAnswer(i -> {
				List<PriceRecord> in = new ArrayList<>();
				((Iterable<PriceRecord>) i.getArgument(0)).forEach(in::add);
				records.addAll(in);
				return in;
			});
			when(normalizedRepo.findByItemIdAndStoreId(anyString(), anyString()))
					.thenAnswer(i -> Optional.ofNullable(normalized.get(i.getArgument(0) + "|" + i.getArgument(1))));
			when(normalizedRepo.findByItemIdIn(anyCollection())).thenAnswer(i -> {
				java.util.Collection<String> ids = i.getArgument(0);
				return normalized.values().stream().filter(n -> ids.contains(n.getItemId())).toList();
			});
			when(normalizedRepo.save(any(NormalizedProduct.class))).thenAnswer(i -> putNormalized(i.getArgument(0)));
			when(normalizedRepo.saveAll(anyIterable())).thenAnswer(i -> {
				List<NormalizedProduct> out = new ArrayList<>();
				for (NormalizedProduct n : (Iterable<NormalizedProduct>) i.getArgument(0)) {
					out.add(putNormalized(n));
				}
				return out;
			});
		}

		private PriceStats putStats(PriceStats s) {
			stats.put(s.getItemId() + "|" + s.getStoreId(), s);
			return s;
		}

		private NormalizedProduct putNormalized(NormalizedProduct n) {
			normalized.put(n.getItemId() + "|" + n.getStoreId(), n);
			return n;
		}

		PriceStatsService service() {
			NormalizedProductService normalizedService = new NormalizedProductService(normalizedRepo,
					mock(UnmatchedSearchTermRepository.class), CommonProductCatalog.loadDefault());
			return new PriceStatsService(new PriceStatsWriter(statsRepo), new PriceBatchWriter(recordRepo, statsRepo),
					normalizedService);
		}

		int calls() {
			return mockingDetails(statsRepo).getInvocations().size() + mockingDetails(recordRepo).getInvocations().size()
					+ mockingDetails(normalizedRepo).getInvocations().size();
		}
	}

	private static PriceRecord record(String code, String price, boolean promo, int minute) {
		return PriceRecord.builder().itemId("newworld:" + code).storeId("newworld:s1").chainId("newworld")
				.channel("pickup").priceAmount(new BigDecimal(price)).currency("NZD").promoFlag(promo)
				.capturedAt(T0.plusMinutes(minute)).createdAt(T0)
				.rawAttributes(Map.of("name", "Anchor Blue Top Milk 2L", "brand", "Anchor")).build();
	}

	private static PriceStats existing(String code, String... prices) {
		List<Map<String, Object>> history = new ArrayList<>();
		for (String p : prices) {
			history.add(new LinkedHashMap<>(Map.of("price", Double.valueOf(p), "capturedAt", T0.minusDays(1).toString())));
		}
		BigDecimal first = new BigDecimal(prices[0]);
		return PriceStats.builder().id(UUID.randomUUID()).itemId("newworld:" + code).storeId("newworld:s1")
				.chainId("newworld").currency("NZD").currentPrice(first).lowestPrice(first).highestPrice(first)
				.medianPrice(first).priceHistory(history).observationCount(prices.length)
				.firstObservedAt(T0.minusDays(1)).lastObservedAt(T0.minusDays(1)).createdAt(T0.minusDays(1))
				.updatedAt(T0.minusDays(1)).build();
	}

	private static void seed(Db db) {
		for (PriceStats s : List.of(existing("A", "3.00", "5.00"), existing("B", "4.00"))) {
			db.putStats(s);
		}
	}

	/** Observations in order: a price change, a promo, three of the same new product, duplicates of an existing one. */
	private static List<PriceRecord> observations() {
		return List.of(record("A", "4.50", false, 0), record("B", "3.50", true, 1), record("N", "2.00", false, 2),
				record("N", "2.50", true, 3), record("N", "1.80", false, 4), record("A", "5.50", true, 5),
				record("C", "9.99", false, 6), record("A", "4.50", false, 7));
	}

	@Test
	void batchLeavesTheSameStatsAndNormalizedRowsAsOneByOne() {
		Db sequential = new Db();
		Db batch = new Db();
		seed(sequential);
		seed(batch);

		observations().forEach(sequential.service()::recordObservation);
		for (PriceRecord r : observations()) {
			sequential.recordRepo.save(r);
		}
		batch.service().saveAndRecordAll(observations());

		assertThat(batch.stats.keySet()).isEqualTo(sequential.stats.keySet());
		sequential.stats.forEach((key, expected) -> {
			PriceStats actual = batch.stats.get(key);
			assertThat(actual).as(key).usingRecursiveComparison()
					.ignoringFields("id", "updatedAt", "createdAt", "priceHistory").isEqualTo(expected);
			assertThat(historyOf(actual)).as(key + " history").isEqualTo(historyOf(expected));
		});
		assertThat(batch.stats.get("newworld:N|newworld:s1").getPriceHistory()).hasSize(3);
		assertThat(batch.stats.get("newworld:N|newworld:s1").getCurrentPrice()).isEqualByComparingTo("1.80");
		assertThat(batch.stats.get("newworld:A|newworld:s1").getObservationCount()).isEqualTo(5);

		assertThat(batch.normalized.keySet()).isEqualTo(sequential.normalized.keySet());
		sequential.normalized.forEach((key, expected) -> assertThat(batch.normalized.get(key)).as(key)
				.usingRecursiveComparison().ignoringFields("id", "updatedAt").isEqualTo(expected));
		assertThat(batch.records).usingRecursiveFieldByFieldElementComparator()
				.containsExactlyElementsOf(sequential.records);
	}

	private static List<String> historyOf(PriceStats s) {
		return s.getPriceHistory().stream()
				.map(e -> new BigDecimal(e.get("price").toString()).stripTrailingZeros().toPlainString() + "@" + e.get("capturedAt"))
				.toList();
	}

	@Test
	void repositoryCallsPerBatchDoNotGrowWithTheNumberOfRows() {
		int small = callsForRows(10);
		int large = callsForRows(300);

		assertThat(large).isEqualTo(small).isLessThanOrEqualTo(8);
	}

	private static int callsForRows(int n) {
		Db db = new Db();
		List<PriceRecord> rows = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			rows.add(record("P" + i, "2.00", false, i));
		}
		db.service().saveAndRecordAll(rows);
		assertThat(db.records).hasSize(n);
		assertThat(db.stats).hasSize(n);
		assertThat(db.normalized).hasSize(n);
		return db.calls();
	}

	/**
	 * Measured micro-benchmark (printed, not asserted beyond the bound): repository calls for a 300-row batch, old
	 * per-row path versus the batch path, and the time that would cost at Neon's ~190 ms round trip.
	 */
	@Test
	void microBenchmarkCallsPerThreeHundredRowBatch() {
		Db old = new Db();
		PriceStatsService oldService = old.service();
		for (int i = 0; i < 300; i++) {
			PriceRecord r = record("P" + i, "2.00", false, i);
			old.recordRepo.save(r);
			oldService.recordObservation(r);
		}
		int before = old.calls();
		int after = callsForRows(300);

		System.out.printf("BENCH 300-row batch: repository calls per-row=%d, batch=%d; at 190 ms/call: %.1f s vs %.1f s%n",
				before, after, before * 0.19, after * 0.19);
		assertThat(after).isLessThan(before / 50);
	}

	@Test
	void aFailingBatchWritesNothingToStatsWhenTheRecordInsertFails() {
		Db db = new Db();
		doThrow(new IllegalStateException("insert failed")).when(db.recordRepo).saveAll(anyIterable());
		PriceStatsService service = db.service();

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.saveAndRecordAll(List.of(record("A", "1.00", false, 0))))
				.isInstanceOf(IllegalStateException.class);

		// The exception propagates (the transaction rolls back), so the scheduler can retry the rows one by one.
		assertThat(db.records).isEmpty();
	}
}
