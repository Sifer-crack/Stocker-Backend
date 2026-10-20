package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.UnmatchedSearchTermRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one-off catch-up must finish the whole backlog, not just the first batch, and must always terminate. */
class NormalizedProductBackfillTest {

	private NormalizedProductRepository repository;
	private NormalizedProductService service;

	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		service = spy(new NormalizedProductService(repository, mock(UnmatchedSearchTermRepository.class),
				CommonProductCatalog.loadDefault()));
		Mockito.doNothing().when(service).observe(Mockito.any());
	}

	private static List<PriceRecord> rows(int count) {
		List<PriceRecord> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(PriceRecord.builder().itemId("paknsave:" + i).build());
		}
		return rows;
	}

	@Test
	void keepsGoingUntilABatchComesBackShort() {
		when(repository.findUnnormalizedRecords(100)).thenReturn(rows(100), rows(100), rows(30));

		int total = service.backfillAll(100, 50);

		assertThat(total).isEqualTo(230);
		verify(repository, times(3)).findUnnormalizedRecords(100);
	}

	@Test
	void aSingleShortBatchIsOneRound() {
		when(repository.findUnnormalizedRecords(100)).thenReturn(rows(7));

		assertThat(service.backfillAll(100, 50)).isEqualTo(7);
		verify(repository, times(1)).findUnnormalizedRecords(100);
	}

	@Test
	void nothingToDoReturnsZero() {
		when(repository.findUnnormalizedRecords(anyInt())).thenReturn(List.of());

		assertThat(service.backfillAll(100, 50)).isZero();
	}

	@Test
	void aSafetyCapStopsABacklogThatNeverShrinks() {
		// e.g. rows that can never be normalised keep coming back full: the loop must still end
		when(repository.findUnnormalizedRecords(100)).thenReturn(rows(100));

		assertThat(service.backfillAll(100, 5)).isEqualTo(500);
		verify(repository, times(5)).findUnnormalizedRecords(100);
	}
}
