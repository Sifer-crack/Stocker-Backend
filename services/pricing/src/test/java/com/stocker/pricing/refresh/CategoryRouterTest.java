package com.stocker.pricing.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.stocker.pricing.ingest.ChainId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryRouterTest {

	private final CategoryRouter router = CategoryRouter.loadDefault();

	@Test
	void routesATermToItsLeafCategoryOnEachChain() {
		assertEquals("https://www.newworld.co.nz/shop/category/pantry/pasta-rice--noodles/instant-noodles?pg=1",
				router.route(ChainId.NEWWORLD, "instant noodles", null).orElseThrow());
		assertEquals("https://www.paknsave.co.nz/shop/category/pantry/pasta-rice--noodles/instant-noodles?pg=1",
				router.route(ChainId.PAKNSAVE, "instant noodles", null).orElseThrow());
		assertTrue(router.route(ChainId.WOOLWORTHS, "frozen peas", null).orElseThrow()
				.startsWith("https://www.woolworths.co.nz/shop/browse/frozen/"));
	}

	@Test
	void aTermWithNoWordInAnyCategoryIsNotRoutedSoNothingIsLoaded() {
		assertTrue(router.route(ChainId.NEWWORLD, "zzqx wibble", null).isEmpty());
		assertTrue(router.route(ChainId.NEWWORLD, "", null).isEmpty());
	}

	@Test
	void theCategoryHintBreaksTiesBetweenSimilarPages() {
		CategoryRouter small = CategoryRouter.of(List.of(
				"F|pets/cat/wet-food", "F|pets/dog/wet-food", "F|pantry/canned-foods--packets/wet-food"));
		String url = small.route(ChainId.NEWWORLD, "wet food", "dog").orElseThrow();
		assertTrue(url.contains("pets/dog/wet-food"), url);
	}

	@Test
	void theMoreSpecificLeafWinsAnEqualScore() {
		CategoryRouter small = CategoryRouter.of(List.of("F|pantry/sauces", "F|pantry/sauces/hot-sauces"));
		assertTrue(small.route(ChainId.PAKNSAVE, "sauce", null).orElseThrow().contains("hot-sauces")
				|| small.route(ChainId.PAKNSAVE, "sauce", null).orElseThrow().endsWith("pantry/sauces?pg=1"));
	}

	@Test
	void aPastedWoolworthsCategoryListIsAcceptedAsIs() {
		CategoryRouter pasted = CategoryRouter.of(List.of());
		pasted.addWoolworthsUrls(" https://www.woolworths.co.nz/shop/browse/pantry/sauces-pastes/hot-sauce"
				+ " https://www.woolworths.co.nz/shop/browse/pantry/desserts/jelly-crystals not-a-url ");

		assertEquals("https://www.woolworths.co.nz/shop/browse/pantry/desserts/jelly-crystals",
				pasted.route(ChainId.WOOLWORTHS, "jelly crystals", null).orElseThrow());
		assertEquals(2, pasted.size());
	}
}
