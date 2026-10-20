package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** B7: a query with an unknown brand token and a size nobody sells still gets the generic products of its type. */
@Tag("service")
class ProductSearchGenericFallbackTest {

	private static final String QUERY = "shaoxingshiye cooking wine 2.5L";

	private static List<NormalizedProduct> wineShelf(boolean keyed) {
		List<NormalizedProduct> rows = List.of(
				row("paknsave", "Double Pagoda Shao Xing Cooking Wine 640ml", "Double Pagoda", "2.59"),
				row("paknsave", "Shiwan Cooking Rice Wine 560ml", "Shiwan", "3.79"),
				row("newworld", "Mama San Mirin Sweet Rice Wine 500ml", "Mama San", "4.50"),
				row("woolworths", "Fountain Rice Wine Vinegar 250ml", "Fountain", "2.20"),
				row("newworld", "Villa Maria Sauvignon Blanc 750ml", "Villa Maria", "14.99"));
		if (!keyed) {
			rows.forEach(r -> r.setCanonicalKey(null));
		}
		return rows;
	}

	@Test
	void unknownBrandAndSizeFallBackToGenericCookingWineAsAlternatives() {
		for (boolean keyed : new boolean[] { true, false }) {
			SearchHarness h = new SearchHarness(wineShelf(keyed).toArray(NormalizedProduct[]::new));

			SearchResult result = h.service.search(QUERY, "item-1", null, 10);

			assertThat(result.products()).as("keyed=" + keyed).isEmpty();
			assertThat(result.alternatives()).extracting(ProductSearchService.Product::name)
					.as("keyed=" + keyed)
					.contains("Double Pagoda Shao Xing Cooking Wine 640ml", "Shiwan Cooking Rice Wine 560ml")
					.doesNotContain("Fountain Rice Wine Vinegar 250ml", "Villa Maria Sauvignon Blanc 750ml");
			assertThat(result.status()).isEqualTo("alternatives-only");
			assertThat(result.matchMethod()).isIn("generic-fallback", "canonical");
		}
	}

	@Test
	void riceWineVinegarIsNeverOffered() {
		SearchHarness h = new SearchHarness(
				row("woolworths", "Fountain Rice Wine Vinegar 250ml", "Fountain", "2.20"),
				row("woolworths", "Kikkoman Rice Wine Vinegar 300ml", "Kikkoman", "3.20"));

		SearchResult result = h.service.search(QUERY, "item-1", null, 10);

		assertThat(result.products()).isEmpty();
		assertThat(result.alternatives()).isEmpty();
		assertThat(result.status()).isEqualTo("not-found");
	}

	@Test
	void nonCommonTermDropsUnknownBrandAndSizeButKeepsTheProductType() {
		SearchHarness h = new SearchHarness(
				row("newworld", "Gourmet Lemongrass Paste 90g", "Gourmet", "3.50"),
				row("paknsave", "Gourmet Ginger Paste 90g", "Gourmet", "3.40"),
				row("woolworths", "Gourmet Lemongrass Stalks 40g", "Gourmet", "3.30"));

		SearchResult result = h.service.search("zorbo lemongrass paste 500g", "item-1", null, 10);

		assertThat(result.products()).isEmpty();
		assertThat(result.matchMethod()).isEqualTo("generic-fallback");
		assertThat(result.alternatives()).extracting(ProductSearchService.Product::name)
				.contains("Gourmet Lemongrass Paste 90g").doesNotContain("Gourmet Lemongrass Stalks 40g");
		assertThat(result.status()).isEqualTo("alternatives-only");
	}
}
