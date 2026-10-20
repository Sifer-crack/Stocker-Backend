package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.NOW;
import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.stocker.pricing.service.search.ProductSearchService.Product;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The owner's manual-test findings (2026-10-19) as scenarios over realistic New World / PAK'nSave / Woolworths names. */
@Tag("service")
class ProductSearchScenarioTest {

	private static List<String> keys(List<Product> products) {
		return products.stream().map(Product::productKey).toList();
	}

	@Test
	void eggsSize7IsOneMergedProductWithThreeChainPricesAndTheRestAreAlternatives() {
		SearchHarness h = new SearchHarness(
				row("newworld", "Farmer Brown Free Range Eggs Size 7 12 Pack", "Farmer Brown", "6.20"),
				row("paknsave", "Farmer Brown Free Range Size 7 Eggs 12pk", "Farmer Brown", "5.90"),
				row("woolworths", "Farmer Brown Free Range Egg Size 7 12 Pack", null, "6.50", true, NOW),
				row("newworld", "Pams Free Range Eggs Size 6 12 Pack", "Pams", "5.40"),
				row("paknsave", "Pams Free Range Eggs Size 6 12 Pack", "Pams", "5.20"),
				row("newworld", "Farmer Brown Free Range Eggs Size 8 12 Pack", "Farmer Brown", "7.10"),
				row("woolworths", "Woolworths Cage Free Eggs Size 6 12 Pack", "Woolworths", "4.90"),
				row("newworld", "Chicken Egg Noodles 500g", null, "3.10"));

		SearchResult result = h.service.search("eggs size 7", "item-1", null, 10);

		assertThat(result.matchMethod()).isEqualTo("canonical");
		assertThat(result.products()).hasSize(1);
		Product eggs = result.products().get(0);
		assertThat(eggs.productKey()).isEqualTo("farmer-brown-free-range-eggs|size-7|12each");
		assertThat(eggs.prices()).containsOnlyKeys("newworld", "paknsave", "woolworths");
		assertThat(eggs.cheapestChainId()).isEqualTo("paknsave");
		assertThat(eggs.unit()).isEqualTo("each");
		assertThat(eggs.unitPrice()).isEqualByComparingTo("0.4917");
		assertThat(eggs.grade()).isEqualTo("7");
		assertThat(eggs.packSize()).isEqualTo("12 pack");
		assertThat(result.query().grade()).isEqualTo("7");
		assertThat(result.query().words()).containsExactly("egg");
		assertThat(result.alternatives()).extracting(Product::name).containsExactlyInAnyOrder(
				"Pams Free Range Eggs Size 6 12 Pack", "Farmer Brown Free Range Eggs Size 8 12 Pack",
				"Woolworths Cage Free Eggs Size 6 12 Pack");
		assertThat(keys(result.alternatives())).doesNotContain(eggs.productKey());
		assertThat(result.alternatives()).extracting(Product::name).doesNotContain("Chicken Egg Noodles 500g");
		// the Pams size 6 eggs are at two chains: still one alternative
		assertThat(result.alternatives().stream().filter(p -> "Pams".equals(p.brand())).toList()).hasSize(1);
	}

	@Test
	void milk2LAndPlainMilkGiveDifferentResults() {
		SearchHarness h = new SearchHarness(
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"),
				row("paknsave", "Anchor Blue Top Fresh Milk 2 litre", "Anchor", "4.60"),
				row("woolworths", "Anchor Blue Top Milk 2L", null, "4.70"),
				row("newworld", "Anchor Blue Top Milk 1l", "Anchor", "2.60"),
				row("paknsave", "Anchor Blue Top Milk 1l", "Anchor", "2.50"),
				row("woolworths", "Anchor Blue Top Milk 3l", null, "6.90"),
				row("paknsave", "Pams Standard Milk 2l", "Pams", "3.90"),
				row("newworld", "Anchor Lite Blue Milk 2l", "Anchor", "4.90"));

		SearchResult sized = h.service.search("Milk 2L", "item-1", null, 10);
		SearchResult plain = h.service.search("Milk", "item-1", null, 10);

		assertThat(sized.products()).allSatisfy(p -> assertThat(p.packSize()).isEqualTo("2L"));
		assertThat(sized.products()).hasSize(3);   // Anchor Blue Top, Pams, Anchor Lite
		assertThat(sized.products().get(0).productKey()).isEqualTo("pams-standard-milk|2000ml");
		assertThat(sized.alternatives()).extracting(Product::packSize).contains("1L", "3L");
		assertThat(plain.products()).extracting(Product::packSize).contains("1L", "2L", "3L");
		assertThat(plain.products()).hasSizeGreaterThan(sized.products().size());
		assertThat(keys(plain.products())).isNotEqualTo(keys(sized.products()));
		Product anchor = sized.products().stream().filter(p -> p.productKey().startsWith("anchor-blue-top-milk")).findFirst().orElseThrow();
		assertThat(anchor.prices()).containsOnlyKeys("newworld", "paknsave", "woolworths");
	}

	@Test
	void timTamIsFoundAtEveryChainAndOtherBiscuitsAreTheAlternatives() {
		SearchHarness h = new SearchHarness(
				row("newworld", "Arnott's Tim Tam Original 200g", "Arnott's", "3.50"),
				row("paknsave", "Tim Tam Arnott's Original 200g", "Arnott's", "3.20"),
				row("woolworths", "Tim Tam Original 200g", "Arnott's", "3.80", true, NOW),
				row("newworld", "Arnott's Tim Tam Double Coat 200g", "Arnott's", "3.60"),
				row("paknsave", "Arnott's Tim Tam Double Coat 200g", "Arnott's", "3.40"),
				row("newworld", "Griffin's Chocolate Biscuits 200g", "Griffin's", "2.90"),
				row("woolworths", "McVitie's Digestive Biscuits 400g", "McVitie's", "3.30"));

		SearchResult result = h.service.search("tim tam", "item-1", null, 10);

		assertThat(result.products()).hasSize(2);
		Product original = result.products().stream().filter(p -> p.name().contains("Original")).findFirst().orElseThrow();
		assertThat(original.prices()).containsOnlyKeys("newworld", "paknsave", "woolworths");
		assertThat(original.cheapestChainId()).isEqualTo("paknsave");
		assertThat(original.unit()).isEqualTo("kg");
		assertThat(result.alternatives()).extracting(Product::brand).containsExactlyInAnyOrder("Griffin's", "McVitie's");
		assertThat(result.alternatives()).extracting(Product::name).noneMatch(name -> name.contains("Tim Tam"));
	}
}
