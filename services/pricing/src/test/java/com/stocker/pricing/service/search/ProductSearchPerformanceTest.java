package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * B8: a warm search is answered from the in-memory copy of the stored products, with no database and no supermarket.
 * The bounds are generous (a shared CI box); the measured numbers go to build/search-profile.txt.
 */
@Tag("service")
class ProductSearchPerformanceTest {

	private static final String[] CHAINS = { "newworld", "paknsave", "woolworths" };
	private static final String[] BRANDS = { "Anchor", "Pams", "Value", "Fresh Choice", "Signature Range", "Mother Earth",
			"Hansells", "Heinz", "Watties", "Sanitarium", "Arnott's", "Griffin's", "Tip Top", "Meadow Fresh" };
	private static final String[] TYPES = { "Milk", "Free Range Eggs", "Cooking Wine", "Tomato Sauce", "Baked Beans",
			"Weet-Bix", "Tim Tam Original", "Ice Cream Vanilla", "Smoked Paprika", "Basmati Rice", "Olive Oil",
			"Chicken Breast", "Fusilli Pasta", "Corn Chips", "Peanut Butter", "Soy Sauce", "Rice Wine Vinegar" };
	private static final String[] SIZES = { "250g", "500g", "1kg", "2l", "1l", "750ml", "12 pack", "400g", "1.5l" };

	private static NormalizedProduct[] fixture(int count) {
		Random random = new Random(42);
		List<NormalizedProduct> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String name = BRANDS[random.nextInt(BRANDS.length)] + " " + TYPES[random.nextInt(TYPES.length)] + " v" + (i / 40)
					+ " " + SIZES[random.nextInt(SIZES.length)];
			rows.add(row(CHAINS[i % 3], name, null, String.valueOf(1 + random.nextInt(2000) / 100.0)));
		}
		return rows.toArray(NormalizedProduct[]::new);
	}

	private static long millis(Runnable work) {
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 5; i++) {
			long start = System.nanoTime();
			work.run();
			best = Math.min(best, (System.nanoTime() - start) / 1_000);
		}
		return best;
	}

	@Test
	void aWarmSearchOverFiveThousandProductsIsFast() throws Exception {
		SearchHarness h = new SearchHarness(fixture(5_000));
		long coldStart = System.nanoTime();
		h.service.search("milk 2l", "cold", null, 10);   // first call: word cache empty, JIT cold
		long cold = (System.nanoTime() - coldStart) / 1_000;

		long common = millis(() -> h.service.search("milk 2l", "item-1", null, 10));
		long lexical = millis(() -> h.service.search("smoked paprika 500g", "item-1", null, 10));
		long fallback = millis(() -> h.service.search("shaoxingshiye cooking wine 2.5L", "item-1", null, 10));
		long nothing = millis(() -> h.service.search("quinoa flakes", "item-1", null, 10));
		SearchResult wine = h.service.search("shaoxingshiye cooking wine 2.5L", "item-1", null, 10);

		String report = "5000 products (microseconds) firstCall=" + cold + "; best of 5 warm runs: common=" + common + " lexical=" + lexical
				+ " genericFallback=" + fallback + " nothing=" + nothing + " fallbackAlternatives=" + wine.alternatives().size()
				+ " status=" + wine.status() + "\n";
		Path out = Path.of("build", "search-profile.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report);
		System.out.print(report);

		assertThat(cold).isLessThan(2_000_000);
		assertThat(common).isLessThan(250_000);
		assertThat(lexical).isLessThan(250_000);
		assertThat(fallback).isLessThan(500_000);
		assertThat(nothing).isLessThan(500_000);
	}
}
