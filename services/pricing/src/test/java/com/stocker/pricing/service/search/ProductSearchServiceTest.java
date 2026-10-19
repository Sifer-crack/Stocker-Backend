package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.NOW;
import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.service.search.ProductSearchService.Product;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import com.stocker.pricing.service.search.ProductSearchService.SelectedProduct;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductSearchServiceTest {

	private static List<NormalizedProduct> milks() {
		return List.of(
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"),
				row("paknsave", "Anchor Blue Top Fresh Milk 2 litre", null, "4.60"),
				row("woolworths", "Anchor Milk Blue Top 2L", "Anchor", "4.70", true, NOW),
				row("newworld", "Anchor Blue Top Milk 1l", "Anchor", "2.60"),
				row("paknsave", "Anchor Blue Top Milk 3l", "Anchor", "6.90"),
				row("paknsave", "Pams Standard Milk 2l", "Pams", "3.90"),
				row("newworld", "Pams Standard Milk 2l", "Pams", "3.95"));
	}

	private static SearchHarness with(List<NormalizedProduct> rows) {
		return new SearchHarness(rows.toArray(NormalizedProduct[]::new));
	}

	private static List<String> names(List<Product> products) {
		return products.stream().map(Product::name).toList();
	}

	@Test
	void theSameProductAtSeveralChainsIsOneProductWithAPricePerChain() {
		SearchResult result = with(milks()).service.search("milk 2L", "item-1", null, 10);

		Product anchor = result.products().stream().filter(p -> "Anchor".equals(p.brand())).findFirst().orElseThrow();
		assertThat(anchor.prices()).containsOnlyKeys("newworld", "paknsave", "woolworths");
		assertThat(anchor.cheapestChainId()).isEqualTo("paknsave");
		assertThat(anchor.productKey()).isEqualTo("anchor-blue-top-milk|2000ml");
		assertThat(anchor.packSize()).isEqualTo("2L");
		assertThat(anchor.unit()).isEqualTo("l");
		assertThat(anchor.unitPrice()).isEqualByComparingTo("2.30");
		assertThat(anchor.prices().get("paknsave").unitPrice()).isEqualByComparingTo("2.30");
		assertThat(anchor.prices().get("woolworths").promo()).isTrue();
		assertThat(anchor.prices().get("newworld").storeId()).isEqualTo("newworld:");
		assertThat(anchor.prices().get("newworld").stale()).isFalse();
		assertThat(result.products().stream().map(Product::productKey)).doesNotHaveDuplicates();
	}

	@Test
	void aSizeInTheTermKeepsOnlyThatSizeAndOtherSizesBecomeAlternatives() {
		SearchResult result = with(milks()).service.search("milk 2L", "item-1", null, 10);

		assertThat(result.products()).allSatisfy(p -> assertThat(p.packSize()).isEqualTo("2L"));
		assertThat(result.products()).hasSize(2);   // Anchor and Pams, 2L each
		assertThat(result.alternatives().stream().map(Product::packSize)).containsExactlyInAnyOrder("1L", "3L");
		assertThat(result.query().size()).isEqualTo("2L");
		assertThat(result.query().words()).containsExactly("milk");
	}

	@Test
	void withoutASizeAllSizesAreMatchesAndTheyAreOrderedByUnitPrice() {
		SearchResult result = with(milks()).service.search("milk", "item-1", null, 10);

		assertThat(result.products()).hasSize(4);
		assertThat(result.query().size()).isNull();
		List<BigDecimal> unitPrices = result.products().stream().map(Product::unitPrice).toList();
		assertThat(unitPrices).isSorted();
		assertThat(result.alternatives()).isEmpty();   // everything of the category already is a match
	}

	@Test
	void alternativesAreNeverAProductThatIsAlreadyAMatch() {
		SearchResult result = with(milks()).service.search("milk 2L", "item-1", null, 10);

		List<String> matchKeys = result.products().stream().map(Product::productKey).toList();
		assertThat(result.alternatives().stream().map(Product::productKey)).doesNotContainAnyElementsOf(matchKeys);
		assertThat(result.alternatives().stream().map(Product::productKey)).doesNotHaveDuplicates();
	}

	@Test
	void whenTheAskedSizeDoesNotExistProductsIsEmptyAndTheOtherSizesAreAlternatives() {
		SearchResult result = with(milks()).service.search("milk 5L", "item-1", null, 10);

		assertThat(result.products()).isEmpty();
		assertThat(result.alternatives()).isNotEmpty();
		assertThat(result.alternatives().stream().map(Product::packSize)).contains("1L", "2L", "3L");
	}

	@Test
	void alternativesAreCappedAtFiveAndLimitCapsTheProducts() {
		List<NormalizedProduct> rows = new java.util.ArrayList<>();
		for (int i = 1; i <= 9; i++) {
			rows.add(row("newworld", "Brand" + (char) ('a' + i) + " Milk " + i + "l", "Brand" + (char) ('a' + i), "" + (2 + i)));
		}
		SearchResult sized = with(rows).service.search("milk 20L", "item-1", null, 10);
		assertThat(sized.products()).isEmpty();
		assertThat(sized.alternatives()).hasSize(5);

		SearchResult limited = with(rows).service.search("milk", "item-1", null, 3);
		assertThat(limited.products()).hasSize(3);
	}

	@Test
	void alternativesAreRankedByRelevanceThenPriceCloseness() {
		List<NormalizedProduct> rows = List.of(
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"),
				row("newworld", "Anchor Blue Top Milk 1l", "Anchor", "2.60"),
				row("newworld", "Anchor Blue Top Milk 3l", "Anchor", "6.90"),
				row("newworld", "Anchor Blue Top Milk 4l", "Anchor", "5.30"));
		SearchResult result = with(rows).service.search("milk 2L", "item-1", null, 10);

		assertThat(names(result.alternatives())).containsExactly(
				"Anchor Blue Top Milk 4l", "Anchor Blue Top Milk 3l", "Anchor Blue Top Milk 1l");
	}

	@Test
	void eggGradeIsAQualifierToo() {
		List<NormalizedProduct> rows = List.of(
				row("newworld", "Farmer Brown Free Range Eggs Size 7 12 Pack", "Farmer Brown", "6.20"),
				row("newworld", "Farmer Brown Free Range Eggs Size 6 12 Pack", "Farmer Brown", "6.00"));
		SearchResult result = with(rows).service.search("eggs size 7", "item-1", null, 10);

		assertThat(result.query().grade()).isEqualTo("7");
		assertThat(result.products()).hasSize(1);
		assertThat(result.products().get(0).grade()).isEqualTo("7");
		assertThat(result.alternatives()).hasSize(1);
		assertThat(result.alternatives().get(0).grade()).isEqualTo("6");
		assertThat(result.query().category()).isEqualTo("eggs");
	}

	@Test
	void packSizeAndUnitPriceAreNullWhenTheNameStatesNone() {
		SearchResult result = with(List.of(row("newworld", "Bananas Fresh", null, "3.50"))).service
				.search("bananas", "item-1", null, 10);

		Product bananas = result.products().get(0);
		assertThat(bananas.packSize()).isNull();
		assertThat(bananas.unit()).isNull();
		assertThat(bananas.unitPrice()).isNull();
		assertThat(bananas.brand()).isNull();
		assertThat(bananas.grade()).isNull();
		assertThat(bananas.prices().get("newworld").unitPrice()).isNull();
	}

	@Test
	void nothingStoredIsAnEmptyResultAndTheTermIsRecorded() {
		SearchHarness h = with(List.of());
		SearchResult result = h.service.search("dragonfruit", "item-1", null, 10);

		assertThat(result.products()).isEmpty();
		assertThat(result.alternatives()).isEmpty();
		verify(h.normalizedService).recordUnmatched("dragonfruit");
		verify(h.priceRecords, never()).save(any(PriceRecord.class));
	}

	@Test
	void anOldPriceIsFlaggedStaleOnItsChainAndOnTheResult() {
		SearchHarness h = with(List.of(
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80", false, NOW.minusDays(3)),
				row("paknsave", "Anchor Blue Top Milk 2l", "Anchor", "4.60")));
		SearchResult result = h.service.search("milk 2L", "item-1", null, 10);

		Product anchor = result.products().get(0);
		assertThat(anchor.prices().get("newworld").stale()).isTrue();
		assertThat(anchor.prices().get("paknsave").stale()).isFalse();
		assertThat(result.stale()).isTrue();
	}

	@Test
	void freshDataIsNotStale() {
		assertThat(with(milks()).service.search("milk 2L", "item-1", null, 10).stale()).isFalse();
	}

	@Test
	void theNewestObservationOfAProductAtAChainWins() {
		SearchResult result = with(List.of(
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "5.50", false, NOW.minusHours(5)),
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"))).service.search("milk 2L", "item-1", null, 10);

		assertThat(result.products().get(0).prices().get("newworld").price()).isEqualByComparingTo("4.80");
	}

	@Test
	void onlyTheFirstProductsChainRowsAreStoredUnderTheItemId() {
		SearchHarness h = with(milks());
		SearchResult result = h.service.search("milk 2L", "item-1", null, 10);

		ArgumentCaptor<PriceRecord> saved = ArgumentCaptor.forClass(PriceRecord.class);
		int chains = result.products().get(0).prices().size();
		verify(h.priceRecords, times(chains)).save(saved.capture());
		assertThat(saved.getAllValues()).allSatisfy(record -> assertThat(record.getItemId()).isEqualTo("item-1"));
		verify(h.stats, times(chains)).recordObservation(any());
		verify(h.events, times(chains)).publishPriceRecordCaptured(any());
	}

	@Test
	void commonProductsAskTheRefreshServiceTheSameWayTheOldMatchDoes() {
		SearchHarness h = with(milks());
		h.service.search("milk 2L", "item-1", null, 10);

		verify(h.refresh).ensureFresh(any(), any());
	}

	@Test
	void blankTermOrItemIdIsRejected() {
		SearchHarness h = with(milks());
		assertThatThrownBy(() -> h.service.search(" ", "item-1", null, 10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> h.service.search("milk", "", null, 10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> h.service.search("milk", null, null, 10)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void selectingAProductStoresItsChainRowsUnderTheItemId() {
		SearchHarness h = with(milks());
		SearchResult result = h.service.search("milk 2L", "item-1", null, 10);
		Product pams = result.products().stream().filter(p -> "Pams".equals(p.brand())).findFirst().orElseThrow();

		SelectedProduct selected = h.service.selectProduct("item-2", pams.productKey());

		assertThat(selected.itemId()).isEqualTo("item-2");
		assertThat(selected.productKey()).isEqualTo(pams.productKey());
		assertThat(selected.chains()).containsExactlyInAnyOrder("newworld", "paknsave");
		ArgumentCaptor<PriceRecord> saved = ArgumentCaptor.forClass(PriceRecord.class);
		verify(h.priceRecords, times(2 + 2)).save(saved.capture());   // 2 from the search (Pams is first), 2 for the selection
		assertThat(saved.getAllValues().stream().filter(r -> "item-2".equals(r.getItemId()))).hasSize(2);
	}

	@Test
	void selectingAnAlternativeByItsKeyWorks() {
		SearchHarness h = with(milks());
		SearchResult result = h.service.search("milk 2L", "item-1", null, 10);
		String oneLitre = result.alternatives().stream().filter(p -> "1L".equals(p.packSize())).findFirst().orElseThrow().productKey();

		assertThat(h.service.selectProduct("item-1", oneLitre).chains()).containsExactly("newworld");
	}

	@Test
	void aKeyWithADifferentWordOrderStillFindsTheProduct() {
		SearchHarness h = with(milks());
		assertThat(h.service.selectProduct("item-1", "milk-top-blue-anchor|2000ml").chains())
				.containsExactlyInAnyOrder("newworld", "paknsave", "woolworths");
	}

	@Test
	void anUnknownKeyIsNotFound() {
		SearchHarness h = with(milks());
		assertThatThrownBy(() -> h.service.selectProduct("item-1", "no-such-product|1000ml"))
				.isInstanceOf(ProductSearchService.ProductNotFoundException.class);
		verify(h.priceRecords, never()).save(any(PriceRecord.class));
	}

	@Test
	void selectRejectsBlankArguments() {
		SearchHarness h = with(milks());
		assertThatThrownBy(() -> h.service.selectProduct("", "x|1l")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> h.service.selectProduct("item-1", " ")).isInstanceOf(IllegalArgumentException.class);
	}
}
