package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.stocker.pricing.model.NormalizedProduct;
import org.junit.jupiter.api.Test;

/** The product key: normalised brand + name words (no size / grade / count) + pack size, equal across chains. */
class ProductIdentityTest {

	@Test
	void keyIsBrandNameWordsAndPackSize() {
		NormalizedProduct milk = row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80");
		assertThat(ProductIdentity.displayKey(milk)).isEqualTo("anchor-blue-top-milk|2000ml");
	}

	@Test
	void eggKeyCarriesGradeAndCount() {
		NormalizedProduct eggs = row("paknsave", "Pams Free Range Eggs Size 7 12 Pack", "Pams", "5.40");
		assertThat(ProductIdentity.displayKey(eggs)).isEqualTo("pams-free-range-eggs|size-7|12each");
	}

	@Test
	void weightKeyUsesGrams() {
		NormalizedProduct biscuits = row("newworld", "Arnott's Tim Tam Original 200g", "Arnott's", "3.50");
		assertThat(ProductIdentity.displayKey(biscuits)).isEqualTo("arnotts-tim-tam-original|200g");
	}

	@Test
	void sameProductWrittenDifferentlyByEachChainHasOneIdentity() {
		String a = ProductIdentity.canonical(row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"));
		String b = ProductIdentity.canonical(row("paknsave", "Anchor Blue Top Fresh Milk 2 litre", null, "4.60"));
		String c = ProductIdentity.canonical(row("woolworths", "Anchor Milk Blue Top 2L", "Anchor", "4.70"));
		String d = ProductIdentity.canonical(row("woolworths", "ANCHOR Blue Top Milk 2000ml", null, "4.70"));
		assertThat(b).isEqualTo(a);
		assertThat(c).isEqualTo(a);
		assertThat(d).isEqualTo(a);
	}

	@Test
	void eggWordOrderPluralsAndCountSpellingsMerge() {
		String a = ProductIdentity.canonical(row("newworld", "Farmer Brown Free Range Eggs Size 7 12 Pack", "Farmer Brown", "6.20"));
		String b = ProductIdentity.canonical(row("paknsave", "Farmer Brown Free Range Size 7 Eggs 12pk", null, "6.10"));
		String c = ProductIdentity.canonical(row("woolworths", "Farmer Brown Free Range Egg 12 Pack Size 7", "Farmer Brown", "6.50"));
		assertThat(b).isEqualTo(a);
		assertThat(c).isEqualTo(a);
	}

	@Test
	void differentBrandSizeGradeOrCountAreDifferentProducts() {
		String base = ProductIdentity.canonical(row("newworld", "Farmer Brown Free Range Eggs Size 7 12 Pack", "Farmer Brown", "6.20"));
		assertThat(ProductIdentity.canonical(row("newworld", "Pams Free Range Eggs Size 7 12 Pack", "Pams", "5.40"))).isNotEqualTo(base);
		assertThat(ProductIdentity.canonical(row("newworld", "Farmer Brown Free Range Eggs Size 6 12 Pack", "Farmer Brown", "6.20"))).isNotEqualTo(base);
		assertThat(ProductIdentity.canonical(row("newworld", "Farmer Brown Free Range Eggs Size 7 6 Pack", "Farmer Brown", "3.20"))).isNotEqualTo(base);
		assertThat(ProductIdentity.canonical(row("newworld", "Anchor Blue Top Milk 1l", "Anchor", "2.60")))
				.isNotEqualTo(ProductIdentity.canonical(row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80")));
	}

	@Test
	void aSizeScrapedIntoTheBrandSlotIsNotABrand() {
		NormalizedProduct milk = row("newworld", "Anchor Blue Top Milk 2l", "2l", "4.80");
		assertThat(ProductIdentity.brandOf(milk)).isNull();
		assertThat(ProductIdentity.displayKey(milk)).isEqualTo("anchor-blue-top-milk|2000ml");
	}

	@Test
	void aDisplayKeyMapsBackToTheSameIdentityEvenInAnotherWordOrder() {
		NormalizedProduct tim = row("newworld", "Arnott's Tim Tam Original 200g", "Arnott's", "3.50");
		String key = ProductIdentity.displayKey(tim);
		assertThat(ProductIdentity.canonicalOfKey(key)).isEqualTo(ProductIdentity.canonical(tim));
		assertThat(ProductIdentity.canonicalOfKey("arnotts-original-tim-tam|200g")).isEqualTo(ProductIdentity.canonical(tim));
		NormalizedProduct eggs = row("paknsave", "Pams Free Range Eggs Size 7 12 Pack", "Pams", "5.40");
		assertThat(ProductIdentity.canonicalOfKey(ProductIdentity.displayKey(eggs))).isEqualTo(ProductIdentity.canonical(eggs));
	}

	@Test
	void productWithoutAPackSizeStillGetsAKey() {
		NormalizedProduct bananas = row("newworld", "Bananas Fresh", null, "3.50");
		assertThat(ProductIdentity.displayKey(bananas)).isEqualTo("bananas");
	}
}
