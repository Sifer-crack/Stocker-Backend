package com.stocker.pricing.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductNormalizerTest {

	private static PackSize size(String text) {
		return PackSizeParser.parse(text).orElse(null);
	}

	@Test
	void parsesWeightsAndVolumesIntoBaseUnits() {
		assertEquals(new PackSize(PackUnit.ML, new BigDecimal("2000")), size("Anchor Milk 2L"));
		assertEquals(new PackSize(PackUnit.ML, new BigDecimal("2000")), size("milk 2 litres"));
		assertEquals(new PackSize(PackUnit.G, new BigDecimal("1500.0")), size("Flour 1.5kg"));
		assertEquals(new PackSize(PackUnit.G, new BigDecimal("309")), size("baby spinach 309g"));
		assertEquals(new PackSize(PackUnit.ML, new BigDecimal("500")), size("Monster Energy 500ml"));
	}

	@Test
	void multipacksMultiplyAndCountsAreOnlyUsedWithoutAMeasure() {
		assertEquals(new PackSize(PackUnit.G, new BigDecimal("425")), size("braised beef instant noodles 5 x 85g"));
		assertEquals(new PackSize(PackUnit.ML, new BigDecimal("3960")), size("Coke 12 x 330ml"));
		assertEquals(new PackSize(PackUnit.EACH, new BigDecimal("30")), size("size 7 eggs 30pack"));
		assertEquals(new PackSize(PackUnit.EACH, new BigDecimal("6")), size("lotte chocolate pie 6p"));
		assertNull(size("agria potatoes"));
	}

	@Test
	void unitPriceIsPerKgPerLitrePerItem() {
		assertEquals(0, new BigDecimal("1.8500").compareTo(size("milk 2L").unitPrice(new BigDecimal("3.70"))));
		assertEquals(0, new BigDecimal("4.0000").compareTo(size("flour 500g").unitPrice(new BigDecimal("2.00"))));
		assertEquals(0, new BigDecimal("0.4000").compareTo(size("eggs 12pk").unitPrice(new BigDecimal("4.80"))));
	}

	@Test
	void structuredCatalogSizeBeatsParsingTheName() {
		assertEquals("2L", PackSize.ofVolumeL(new BigDecimal("2.0000")).label());
		assertEquals("500g", PackSize.ofWeightKg(new BigDecimal("0.5000")).label());
		assertNull(PackSize.ofWeightKg(null));
	}

	@Test
	void namesLoseSizesNoiseAndSpellingVariation() {
		assertEquals(Set.of("anchor", "blue", "top", "milk"), ProductNormalizer.words("Anchor Blue Top Milk 2L"));
		assertEquals(ProductNormalizer.words("greek yoghurt"), ProductNormalizer.words("Greek Yogurt"));
		assertEquals(ProductNormalizer.words("red onion"), ProductNormalizer.words("red onions not bagged"));
		assertEquals(ProductNormalizer.words("tomato"), ProductNormalizer.words("fresh tomatoes"));
		assertTrue(ProductNormalizer.words("Kellogg's Cornflakes").contains("kellogg"));
	}

	@Test
	void commonListLoadsAndMapsStoreAndPantryNamesToTheSameProduct() {
		CommonProductCatalog catalog = CommonProductCatalog.loadDefault();
		assertTrue(catalog.all().size() > 140);

		assertEquals("milk", key(catalog, "Anchor Blue Top Milk 2L"));
		assertEquals("milk", key(catalog, "full cream milk"));
		assertEquals("milk", key(catalog, "meadow fresh milk original"));
		assertEquals("milk-coconut", key(catalog, "aroy d coconut milk"));
		assertEquals("milk-oat", key(catalog, "Oatly Oat Milk 1L"));
		assertNull(key(catalog, "Cadbury Chocolate Milk 1L"), "flavoured milk is not plain milk");
		assertEquals("eggs", key(catalog, "size 7 eggs 30pack"));
		assertEquals("eggs", key(catalog, "free range eggs 12pack mixed grade"));
		assertEquals("apples", key(catalog, "royal gala apple"));
		assertEquals("apples", key(catalog, "fuji apple in bag"));
		assertEquals("chicken-breast", key(catalog, "chicken breast skin off"));
		assertEquals("chicken-drumstick", key(catalog, "chicken pistol leg"));
		assertEquals("onions", key(catalog, "brown onions 1kg"));
		assertEquals("red-onion", key(catalog, "red onions not bagged"));
		assertEquals("soy-sauce", key(catalog, "tiger king soy sauce"));
		assertEquals("rice-white", key(catalog, "royal india extra long basmati rice"));
		assertEquals("butter", key(catalog, "Anchor Butter Salted 500g"));
		assertEquals("peanut-butter", key(catalog, "Pic's Peanut Butter 380g"));
	}

	private static String key(CommonProductCatalog catalog, String text) {
		Optional<CommonProduct> match = catalog.match(text);
		return match.map(CommonProduct::key).orElse(null);
	}
}
