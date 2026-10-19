package com.stocker.pricing.normalize;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adding a grocery item is one line in common-products.txt and one line in category-map.txt. These checks make a
 * forgotten or mistyped line fail here, offline, instead of silently never being crawled.
 */
class CatalogConsistencyTest {

	private static List<String[]> lines(String resource) throws Exception {
		List<String[]> rows = new ArrayList<>();
		try (InputStream in = CatalogConsistencyTest.class.getResourceAsStream(resource)) {
			assertThat(in).as(resource).isNotNull();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank() && !line.startsWith("#")) {
					rows.add(line.split("\\|", -1));
				}
			}
		}
		return rows;
	}

	private static Set<String> keys(List<String[]> rows) {
		Set<String> keys = new HashSet<>();
		rows.forEach(row -> keys.add(row[0].trim()));
		return keys;
	}

	@Test
	void everyCommonProductHasACrawlPage() throws Exception {
		Set<String> products = keys(lines("/common-products.txt"));
		Set<String> mapped = keys(lines("/category-map.txt"));

		Set<String> missing = new HashSet<>(products);
		missing.removeAll(mapped);

		assertThat(missing).as("common products with no line in category-map.txt (they would never be crawled)").isEmpty();
	}

	@Test
	void everyMappedProductIsInTheCatalog() throws Exception {
		Set<String> products = keys(lines("/common-products.txt"));
		Set<String> mapped = keys(lines("/category-map.txt"));

		mapped.removeAll(products);

		assertThat(mapped).as("category-map.txt lines for products that are not in common-products.txt").isEmpty();
	}

	@Test
	void productKeysAreUnique() throws Exception {
		List<String[]> rows = lines("/common-products.txt");

		assertThat(keys(rows)).hasSameSizeAs(rows);
	}

	@Test
	void everyMappedPageExistsInTheChainsSitemapIndex() throws Exception {
		Set<String> foodstuffs = new HashSet<>();
		Set<String> woolworths = new HashSet<>();
		for (String[] row : lines("/category-index.txt")) {
			if (row.length == 2) {   // "F|<path>" (New World + PAK'nSave) or "W|<path>" (Woolworths)
				("W".equals(row[0].trim()) ? woolworths : foodstuffs).add(row[1].trim());
			}
		}
		List<String> unknown = new ArrayList<>();
		for (String[] row : lines("/category-map.txt")) {
			if (!"-".equals(row[1].trim()) && !foodstuffs.contains(row[1].trim())) {
				unknown.add(row[0] + " (New World/PAK'nSave): " + row[1]);
			}
			if (!"-".equals(row[2].trim()) && !woolworths.contains(row[2].trim())) {
				unknown.add(row[0] + " (Woolworths): " + row[2]);
			}
		}

		assertThat(unknown).as("mapped pages that are not in category-index.txt (typo, or the page was renamed)").isEmpty();
	}
}
