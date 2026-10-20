package com.stocker.pricing.normalize;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A product must be an instance of the category, not a dish that merely contains the word. */
class CommonProductGuardTest {

	private final CommonProductCatalog catalog = CommonProductCatalog.loadDefault();

	private String keyOf(String name) {
		return catalog.match(name).map(CommonProduct::key).orElse("-");
	}

	@Test
	void dishesAndLookAlikesAreNotEggs() {
		List<String> notEggs = List.of("Ben's Original Microwave Rice Egg Fried Rice 250g Pouch", "Egg Fried Rice 250g",
				"Egg Noodles 300g", "Egg Mayonnaise 400g", "Chocolate Easter Egg 100g", "Egg Salad Sandwich", "Eggplant",
				"Gregg's Seasoning 50g", "Leggo's Pasta Sauce 500g", "Egg Sauce 200ml", "Egg Burger Patties");
		for (String name : notEggs) {
			assertThat(keyOf(name)).as(name).isNotEqualTo("eggs");
		}
	}

	@Test
	void realEggsAreEggs() {
		for (String name : List.of("Pams Free Range Eggs Size 7 12 Pack", "Eggs Cage Free 6 Pack",
				"Farm Fresh Barn Eggs Size 6 12pk", "Eggs Free Range S7 12pk", "Eggs x12", "Eggs Dozen")) {
			assertThat(keyOf(name)).as(name).isEqualTo("eggs");
		}
	}

	@Test
	void friedRiceDishesAreNotPlainRiceButRealRiceIs() {
		for (String name : List.of("Ben's Original Microwave Rice Egg Fried Rice 250g Pouch", "Egg Fried Rice 250g",
				"Chicken Fried Rice 350g")) {
			assertThat(keyOf(name)).as(name).isNotEqualTo("rice-white");
		}
		for (String name : List.of("Pams Long Grain Rice 1kg", "Sunrice Jasmine Rice 2kg", "Basmati Rice 1kg")) {
			assertThat(keyOf(name)).as(name).isEqualTo("rice-white");
		}
	}

	@Test
	void aDishWordBeforeTheCategoryWordDoesNotDisqualify() {
		assertThat(keyOf("Vogels Sandwich Slice Bread 700g")).isEqualTo("bread");
	}

	@Test
	void theCategorysOwnDishWordsAreAllowed() {
		assertThat(keyOf("Barilla Pasta Sauce Napoletana 400g")).isEqualTo("pasta-sauce");
		assertThat(keyOf("Heinz Tomato Soup 300g")).isEqualTo("soup");
		assertThat(keyOf("Value Mayonnaise 500g")).isEqualTo("mayonnaise");
	}

	@Test
	void aChocolateBlockNamedDairyMilkIsChocolateNotExcludedAsMilk() {
		assertThat(keyOf("Cadbury Dairy Milk Chocolate Block 180g")).isEqualTo("dairy-milk");
		assertThat(keyOf("Cadbury Roses Milk Chocolate Block 180g")).isEqualTo("chocolate");
		assertThat(keyOf("Whittaker's Creamy Milk Chocolate 250g")).isEqualTo("chocolate");
	}

	@Test
	void timTamsHaveTheirOwnEntryInAnyWriting() {
		for (String name : List.of("Arnott's Tim Tam Original 200g", "Tim Tams Double Coat 200g", "Arnott's TimTam 200g",
				"tim tam", "tim tams", "timtam", "Tim-Tam Caramel")) {
			assertThat(keyOf(name)).as(name).isEqualTo("tim-tams");
		}
		assertThat(keyOf("Griffins Chocolate Biscuits 250g")).isEqualTo("biscuits");
	}

	@Test
	void everyAliasOfEveryProductStillMatchesItselfNothingIsExcludedByItsOwnRules() {
		for (CommonProduct product : catalog.all()) {
			for (java.util.Set<String> alias : product.aliasWords()) {
				String text = String.join(" ", alias);
				CommonProduct hit = catalog.match(text).orElse(null);
				assertThat(hit).as("alias '%s' of %s", text, product.key()).isNotNull();
			}
		}
	}

	@Test
	void popularProductsAreNotDroppedByExcludeLists() {
		assertThat(keyOf("Anchor Blue Top Milk 2L")).isEqualTo("milk");
		assertThat(keyOf("Meadow Fresh Original Milk 1L")).isEqualTo("milk");
		assertThat(keyOf("Cadbury Chocolate Milk 1L")).isEqualTo("-");
		assertThat(keyOf("Tip Top Vanilla Ice Cream 2L")).isEqualTo("ice-cream");
		assertThat(keyOf("Arnott's Chocolate Ripple Biscuits 250g")).isEqualTo("biscuits");
		assertThat(keyOf("Sanitarium Weet-Bix 375g")).isEqualTo("weetbix");
		assertThat(keyOf("Pineapple Lumps 180g")).isEqualTo("pineapple-lumps");
		assertThat(keyOf("Fresh Pineapple Whole")).isEqualTo("pineapple");
		assertThat(keyOf("Milo Chocolate Malt Drink 400g")).isEqualTo("milo");
	}
}
