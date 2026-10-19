package com.stocker.pricing.refresh;

import com.stocker.pricing.ingest.ChainId;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The category map is the single list of pages worth crawling: adding a product's line adds its page to the crawl. */
class CategoryMapUrlsTest {

	private static CategoryMap map(String text) throws Exception {
		return CategoryMap.parse(new BufferedReader(new StringReader(text)));
	}

	@Test
	void listsEachPageOnceInTheOrderOfTheFile() throws Exception {
		CategoryMap map = map("""
				# key|foodstuffs path|woolworths path
				milk|dairy/milk|fridge/milk
				milk-oat|dairy/milk|fridge/oat-milk
				eggs|dairy/eggs|fridge/eggs
				""");

		assertThat(map.urls(ChainId.NEWWORLD)).containsExactly(
				"https://www.newworld.co.nz/shop/category/dairy/milk?pg=1",
				"https://www.newworld.co.nz/shop/category/dairy/eggs?pg=1");
		assertThat(map.urls(ChainId.PAKNSAVE)).containsExactly(
				"https://www.paknsave.co.nz/shop/category/dairy/milk?pg=1",
				"https://www.paknsave.co.nz/shop/category/dairy/eggs?pg=1");
		assertThat(map.urls(ChainId.WOOLWORTHS)).containsExactly(
				"https://www.woolworths.co.nz/shop/browse/fridge/milk",
				"https://www.woolworths.co.nz/shop/browse/fridge/oat-milk",
				"https://www.woolworths.co.nz/shop/browse/fridge/eggs");
	}

	@Test
	void aDashMeansTheChainDoesNotSellItAndIsSkipped() throws Exception {
		CategoryMap map = map("wine|-|booze/wine\nbread|bakery/bread|-\n");

		assertThat(map.urls(ChainId.NEWWORLD)).containsExactly("https://www.newworld.co.nz/shop/category/bakery/bread?pg=1");
		assertThat(map.urls(ChainId.WOOLWORTHS)).containsExactly("https://www.woolworths.co.nz/shop/browse/booze/wine");
	}

	@Test
	void anEmptyMapHasNoPages() {
		assertThat(CategoryMap.empty().urls(ChainId.NEWWORLD)).isEmpty();
		assertThat(CategoryMap.empty().size()).isZero();
	}

	@Test
	void theShippedMapCoversEveryChainWithUniquePages() {
		CategoryMap shipped = CategoryMap.loadDefault();

		for (ChainId chain : ChainId.values()) {
			List<String> urls = shipped.urls(chain);
			assertThat(urls).as(chain.name()).hasSizeGreaterThan(100);
			assertThat(new HashSet<>(urls)).as(chain + " has no duplicates").hasSameSizeAs(urls);
		}
	}
}
