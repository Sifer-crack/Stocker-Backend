package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.refresh.RefreshState;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** B7/B8: the response says whether the search is finished (status) and, while refreshing, how each chain is doing. */
@Tag("service")
class ProductSearchStatusTest {

	@Test
	void foundWhenThereAreProducts() {
		SearchHarness h = new SearchHarness(row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"));

		SearchResult result = h.service.search("milk 2l", "item-1", null, 10);

		assertThat(result.status()).isEqualTo("found");
		assertThat(result.refresh()).isEmpty();
	}

	@Test
	void notFoundWhenNothingIsStoredAndNoRefreshIsRunning() {
		SearchHarness h = new SearchHarness();

		SearchResult result = h.service.search("quinoa flakes", "item-1", null, 10);

		assertThat(result.products()).isEmpty();
		assertThat(result.alternatives()).isEmpty();
		assertThat(result.status()).isEqualTo("not-found");
	}

	@Test
	void refreshingWhileAChainsPageIsStillLoadingAndTheStatesAreExposed() {
		SearchHarness h = new SearchHarness();
		when(h.refresh.progressForTerm(anyString())).thenReturn(Map.of(ChainId.NEWWORLD, RefreshState.PENDING,
				ChainId.PAKNSAVE, RefreshState.DONE, ChainId.WOOLWORTHS, RefreshState.SKIPPED));

		SearchResult result = h.service.search("quinoa flakes", "item-1", null, 10);

		assertThat(result.status()).isEqualTo("refreshing");
		assertThat(result.refresh()).containsOnly(Map.entry("newworld", "pending"), Map.entry("paknsave", "done"),
				Map.entry("woolworths", "skipped"));
	}

	@Test
	void notFoundOnceEveryRefreshHasFinished() {
		SearchHarness h = new SearchHarness();
		when(h.refresh.progressForTerm(anyString())).thenReturn(Map.of(ChainId.NEWWORLD, RefreshState.DONE,
				ChainId.PAKNSAVE, RefreshState.FAILED, ChainId.WOOLWORTHS, RefreshState.DONE));

		SearchResult result = h.service.search("quinoa flakes", "item-1", null, 10);

		assertThat(result.status()).isEqualTo("not-found");
		assertThat(result.refresh()).containsEntry("paknsave", "failed");
	}

	@Test
	void foundStaysFoundWhileAChainIsStillRefreshing() {
		SearchHarness h = new SearchHarness(row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"));
		when(h.refresh.progressForCommon(any())).thenReturn(Map.of(ChainId.PAKNSAVE, RefreshState.PENDING));

		SearchResult result = h.service.search("milk 2l", "item-1", null, 10);

		assertThat(result.status()).isEqualTo("found");
		assertThat(result.refresh()).containsOnly(Map.entry("paknsave", "pending"));
	}

	@Test
	void alternativesOnlyWhenOnlyOtherSizesExist() {
		SearchHarness h = new SearchHarness(row("newworld", "Anchor Blue Top Milk 1l", "Anchor", "2.60"));

		SearchResult result = h.service.search("milk 2l", "item-1", null, 10);

		assertThat(result.products()).isEmpty();
		assertThat(result.alternatives()).isNotEmpty();
		assertThat(result.status()).isEqualTo("alternatives-only");
	}
}
