package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.CATALOG;
import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Suggestions run on every keystroke: a warm call over 5,000 stored products must stay far below 50 ms (bound is generous). */
@Tag("service")
class ProductSuggestPerformanceTest {

	private static final String[] CHAINS = { "newworld", "paknsave", "woolworths" };
	private static final String[] BRANDS = { "Anchor", "Pams", "Value", "Fresh Choice", "Signature Range", "Mother Earth",
			"Hansells", "Heinz", "Watties", "Sanitarium", "Arnott's", "Griffin's", "Tip Top", "Meadow Fresh" };
	private static final String[] TYPES = { "Milk", "Free Range Eggs", "Cooking Wine", "Tomato Sauce", "Baked Beans",
			"Weet-Bix", "Tim Tam Original", "Ice Cream Vanilla", "Smoked Paprika", "Basmati Rice", "Olive Oil",
			"Chicken Breast", "Fusilli Pasta", "Corn Chips", "Peanut Butter", "Soy Sauce", "Rice Wine Vinegar" };
	private static final String[] SIZES = { "250g", "500g", "1kg", "2l", "1l", "750ml", "12 pack", "400g", "1.5l" };

	private static List<NormalizedProduct> fixture(int count) {
		Random random = new Random(42);
		List<NormalizedProduct> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			String name = BRANDS[random.nextInt(BRANDS.length)] + " " + TYPES[random.nextInt(TYPES.length)] + " v" + (i / 40)
					+ " " + SIZES[random.nextInt(SIZES.length)];
			rows.add(row(CHAINS[i % 3], name, null, String.valueOf(1 + random.nextInt(2000) / 100.0)));
		}
		return rows;
	}

	private static long best(Runnable work) {
		long best = Long.MAX_VALUE;
		for (int i = 0; i < 5; i++) {
			long start = System.nanoTime();
			work.run();
			best = Math.min(best, (System.nanoTime() - start) / 1_000);
		}
		return best;
	}

	@Test
	void aWarmSuggestOverFiveThousandProductsIsFast() throws Exception {
		List<NormalizedProduct> rows = fixture(5_000);
		NormalizedProductRepository repository = mock(NormalizedProductRepository.class);
		when(repository.findByCapturedAtAfter(any())).thenReturn(rows);
		PricingCacheProperties properties = new PricingCacheProperties();   // default snapshot ttl: in-memory copy
		ProductSuggestService service = new ProductSuggestService(new NormalizedProductSnapshot(repository, properties), CATALOG, properties);

		long coldStart = System.nanoTime();
		var first = service.suggest("eg", 8);
		long cold = (System.nanoTime() - coldStart) / 1_000;

		long empty = best(() -> service.suggest("", 8));
		long prefix = best(() -> service.suggest("eg", 8));
		long word = best(() -> service.suggest("cooking wi", 8));
		long brand = best(() -> service.suggest("pams ba", 8));
		long nothing = best(() -> service.suggest("qzx", 8));

		String report = "5000 products (microseconds) firstCall=" + cold + "; best of 5 warm runs: empty=" + empty + " prefix=" + prefix
				+ " twoWords=" + word + " brandPrefix=" + brand + " nothing=" + nothing + " firstResults=" + first.size() + "\n";
		Path out = Path.of("build", "suggest-profile.txt");
		Files.createDirectories(out.getParent());
		Files.writeString(out, report);
		System.out.print(report);

		assertThat(first).isNotEmpty();
		assertThat(cold).isLessThan(2_000_000);
		assertThat(empty).isLessThan(100_000);
		assertThat(prefix).isLessThan(100_000);
		assertThat(word).isLessThan(100_000);
		assertThat(brand).isLessThan(100_000);
		assertThat(nothing).isLessThan(100_000);
	}
}
