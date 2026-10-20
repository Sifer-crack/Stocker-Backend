package com.stocker.pricing.normalize;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Obvious staples must get a canonical key. The fixture (canonical-staples.txt) is a list of realistic store names and
 * the key each belongs under; a name with no key, or the wrong one, is listed in one failure message.
 */
class CanonicalStaplesTest {

	private static final CommonProductCatalog CATALOG = CommonProductCatalog.loadDefault();

	private static List<String[]> fixture() throws Exception {
		List<String[]> rows = new ArrayList<>();
		try (InputStream in = CanonicalStaplesTest.class.getResourceAsStream("/canonical-staples.txt")) {
			assertThat(in).isNotNull();
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

	@Test
	void everyFixtureNameGetsItsExpectedKey() throws Exception {
		List<String> wrong = new ArrayList<>();
		for (String[] row : fixture()) {
			String actual = CATALOG.match(row[0]).map(CommonProduct::key).orElse(null);
			if (actual == null || !Set.of(row[1].split("/")).contains(actual)) {
				wrong.add(row[0] + " -> " + actual + " (expected " + row[1] + ")");
			}
		}
		assertThat(wrong).as("names with no key or the wrong key").isEmpty();
	}

	@Test
	void everyExpectedKeyExistsInTheCatalog() throws Exception {
		List<String> missing = new ArrayList<>();
		for (String[] row : fixture()) {
			for (String key : row[1].split("/")) {
				if (CATALOG.byKey(key).isEmpty()) {
					missing.add(key);
				}
			}
		}
		assertThat(missing).isEmpty();
	}
}
