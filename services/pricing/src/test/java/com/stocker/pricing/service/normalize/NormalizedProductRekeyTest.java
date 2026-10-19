package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.UnmatchedSearchTermRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The database-only re-key pass: recomputes derived columns with the current catalog, writes only changed rows. */
class NormalizedProductRekeyTest {

	private NormalizedProductRepository repository;
	private NormalizedProductService service;

	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		service = new NormalizedProductService(repository, mock(UnmatchedSearchTermRepository.class),
				CommonProductCatalog.loadDefault());
	}

	private NormalizedProduct row(String name, String key) {
		return NormalizedProduct.builder().id(UUID.randomUUID()).itemId("paknsave:" + name.hashCode())
				.storeId("s1").chainId("paknsave").canonicalKey(key).normalizedName("stale").displayName(name)
				.priceAmount(new BigDecimal("4.50")).currency("NZD").capturedAt(OffsetDateTime.now())
				.updatedAt(OffsetDateTime.now().minusDays(2)).build();
	}

	private void pages(int size, List<NormalizedProduct> all) {
		when(repository.findAll(any(Pageable.class))).thenAnswer(inv -> {
			Pageable p = inv.getArgument(0);
			int from = (int) p.getOffset();
			int to = Math.min(all.size(), from + p.getPageSize());
			return new PageImpl<>(from >= all.size() ? List.of() : new ArrayList<>(all.subList(from, to)), p, all.size());
		});
	}

	@SuppressWarnings("unchecked")
	private List<NormalizedProduct> saved() {
		ArgumentCaptor<List<NormalizedProduct>> c = ArgumentCaptor.forClass(List.class);
		verify(repository, org.mockito.Mockito.atLeastOnce()).saveAll(c.capture());
		return c.getAllValues().stream().flatMap(List::stream).toList();
	}

	@Test
	void eggFriedRiceFiledUnderEggsIsRekeyedAwayFromEggs() {
		NormalizedProduct r = row("Ben's Original Microwave Rice Egg Fried Rice 250g Pouch", "eggs");
		pages(10, List.of(r));

		var result = service.rekeyAll(10);

		// the dish guard keeps it out of eggs; the current catalog files it under rice-white (that is the catalog's call)
		assertThat(r.getCanonicalKey()).isNotEqualTo("eggs");
		assertThat(result.read()).isEqualTo(1);
		assertThat(result.changed()).isEqualTo(1);
		assertThat(result.keyChanged()).isEqualTo(1);
		assertThat(saved()).containsExactly(r);
	}

	@Test
	void timTamMovesFromBiscuitsToTimTams() {
		NormalizedProduct r = row("Arnott's Tim Tam Original 200g", "biscuits");
		pages(10, List.of(r));

		service.rekeyAll(10);

		assertThat(r.getCanonicalKey()).isEqualTo("tim-tams");
		assertThat(r.getPackUnit()).isEqualTo("g");
		assertThat(r.getPackQuantity()).isEqualByComparingTo("200");
		assertThat(r.getUnitPrice()).isNotNull();
	}

	@Test
	void aCorrectRowIsUntouchedAndNotSaved() {
		NormalizedProduct r = row("Arnott's Tim Tam Original 200g", "tim-tams");
		pages(10, List.of(r));
		service.rekeyAll(10); // brings derived columns up to date
		verify(repository).saveAll(any());
		org.mockito.Mockito.clearInvocations(repository);
		OffsetDateTime stamp = r.getUpdatedAt();

		var second = service.rekeyAll(10);

		assertThat(second.changed()).isZero();
		assertThat(second.keyChanged()).isZero();
		assertThat(r.getUpdatedAt()).isEqualTo(stamp);
		verify(repository, never()).saveAll(any());
	}

	@Test
	void unparsableRowsAreSkippedWithoutFailingTheRun() {
		NormalizedProduct blank = row("x", "eggs");
		blank.setDisplayName("  ");
		NormalizedProduct noPrice = row("Arnott's Tim Tam Original 200g", "biscuits");
		noPrice.setPriceAmount(null);
		NormalizedProduct good = row("Arnott's Tim Tam Original 200g", "biscuits");
		pages(10, List.of(blank, noPrice, good));

		var result = service.rekeyAll(10);

		assertThat(result.read()).isEqualTo(3);
		assertThat(result.changed()).isEqualTo(1);
		assertThat(result.failedPages()).isZero();
		assertThat(blank.getCanonicalKey()).isEqualTo("eggs");
		assertThat(noPrice.getCanonicalKey()).isEqualTo("biscuits");
	}

	@Test
	void walksAllPagesInBatches() {
		List<NormalizedProduct> all = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			all.add(row("Arnott's Tim Tam Original 200g v" + i, "biscuits"));
		}
		pages(3, all);

		var result = service.rekeyAll(3);

		assertThat(result.read()).isEqualTo(7);
		assertThat(result.changed()).isEqualTo(7);
		ArgumentCaptor<Pageable> p = ArgumentCaptor.forClass(Pageable.class);
		verify(repository, org.mockito.Mockito.times(3)).findAll(p.capture());
		assertThat(p.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1, 2);
		assertThat(p.getAllValues()).allSatisfy(x -> assertThat(x.getPageSize()).isEqualTo(3));
		verify(repository, org.mockito.Mockito.times(3)).saveAll(any());
	}

	@Test
	void aFailedPageSaveIsReportedAndTheRunContinues() {
		List<NormalizedProduct> all = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			all.add(row("Arnott's Tim Tam Original 200g v" + i, "biscuits"));
		}
		pages(2, all);
		when(repository.saveAll(any())).thenThrow(new IllegalStateException("neon down")).thenAnswer(i -> i.getArgument(0));

		var result = service.rekeyAll(2);

		assertThat(result.read()).isEqualTo(4);
		assertThat(result.failedPages()).isEqualTo(1);
		assertThat(result.changed()).isEqualTo(2);
		assertThat(result.keyChanged()).isEqualTo(2);
	}

	@Test
	void aFailedReadStopsTheRunAndIsReported() {
		when(repository.findAll(any(Pageable.class))).thenThrow(new IllegalStateException("neon down"));

		var result = service.rekeyAll(10);

		assertThat(result.read()).isZero();
		assertThat(result.failedPages()).isEqualTo(1);
	}
}
