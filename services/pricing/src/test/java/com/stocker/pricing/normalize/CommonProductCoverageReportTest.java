package com.stocker.pricing.normalize;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Read-only report: runs the normalizer over every catalog product (and the pantry-referenced ones)
 * in the configured database and writes which common-product keys they map to and which names map
 * to none. Makes no request to any supermarket. Run with STOCKER_COVERAGE_REPORT=<output file>.
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_COVERAGE_REPORT", matches = ".+")
class CommonProductCoverageReportTest {

	@Test
	void writeCoverageReport() throws Exception {
		CommonProductCatalog catalog = CommonProductCatalog.loadDefault();
		Map<String, Integer> perKey = new TreeMap<>();
		Map<String, Integer> pantryPerKey = new TreeMap<>();
		List<String> unmatched = new ArrayList<>();
		List<String> pantryUnmatched = new ArrayList<>();
		int total = 0;
		int matched = 0;
		int sizeKnown = 0;

		try (Connection db = DriverManager.getConnection(System.getenv("STOCKER_DB_URL"),
				System.getenv("STOCKER_DB_USER"), System.getenv("STOCKER_DB_PASSWORD"));
				Statement statement = db.createStatement();
				ResultSet rs = statement.executeQuery("""
						select p.product_name, p.grocery_type, p.selling_weight_kg, p.selling_volume_l,
						       coalesce((select sum(pi.quantity) from pantry_items pi where pi.product_id = p.product_id), 0) pantry
						from products p order by p.product_name""")) {
			while (rs.next()) {
				String name = rs.getString(1);
				BigDecimal weight = rs.getBigDecimal(3);
				BigDecimal volume = rs.getBigDecimal(4);
				boolean inPantry = rs.getLong(5) > 0;
				total++;
				PackSize size = PackSize.ofWeightKg(weight) != null ? PackSize.ofWeightKg(weight)
						: PackSize.ofVolumeL(volume) != null ? PackSize.ofVolumeL(volume) : ProductNormalizer.sizeOf(name);
				if (size != null) {
					sizeKnown++;
				}
				var common = catalog.match(name);
				if (common.isPresent()) {
					matched++;
					perKey.merge(common.get().key(), 1, Integer::sum);
					if (inPantry) {
						pantryPerKey.merge(common.get().key(), 1, Integer::sum);
					}
				} else {
					unmatched.add(rs.getString(2) + " | " + name);
					if (inPantry) {
						pantryUnmatched.add(rs.getString(2) + " | " + name);
					}
				}
			}
		}

		StringBuilder report = new StringBuilder();
		report.append("catalog products: ").append(total).append(", mapped to a common product: ").append(matched)
				.append(", pack size known: ").append(sizeKnown).append("\n");
		report.append("distinct common products hit: ").append(perKey.size()).append(" of ").append(catalog.all().size())
				.append("\n\n== common products needed (catalog count; pantry count) ==\n");
		perKey.forEach((key, count) -> report.append(key).append(" ").append(count).append("; ")
				.append(pantryPerKey.getOrDefault(key, 0)).append("\n"));
		report.append("\n== pantry products with NO common product (").append(pantryUnmatched.size()).append(") ==\n");
		pantryUnmatched.forEach(line -> report.append(line).append("\n"));
		report.append("\n== all catalog products with NO common product (").append(unmatched.size()).append(") ==\n");
		unmatched.forEach(line -> report.append(line).append("\n"));
		report.append("\n== ingested store listings in price_records -> common product, parsed size ==\n");
		try (Connection db = DriverManager.getConnection(System.getenv("STOCKER_DB_URL"),
				System.getenv("STOCKER_DB_USER"), System.getenv("STOCKER_DB_PASSWORD"));
				Statement statement = db.createStatement();
				ResultSet rs = statement.executeQuery("""
						select distinct chain_id, raw_attributes->>'name', raw_attributes->>'brand'
						from price_records where item_id like '%:%' order by 1, 2""")) {
			while (rs.next()) {
				String brand = rs.getString(3);
				String name = rs.getString(2);
				if (name == null) {
					continue;
				}
				if (brand != null && brand.trim().matches("(?i)\\d+(?:\\.\\d+)?\\s*(?:l|ml|g|kg)")) {
					name = name + " " + brand.trim();
				}
				PackSize size = ProductNormalizer.sizeOf(name);
				report.append(rs.getString(1)).append(" | ").append(name).append(" -> ")
						.append(catalog.match(name).map(CommonProduct::key).orElse("NONE"))
						.append(" | ").append(size == null ? "no size" : size.label()).append("\n");
			}
		}
		Files.writeString(Path.of(System.getenv("STOCKER_COVERAGE_REPORT")), report.toString());
	}
}
