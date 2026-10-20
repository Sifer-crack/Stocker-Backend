package com.stocker.pricing.refresh;

import com.stocker.pricing.ingest.ChainId;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Which exact category page holds a common product on each chain (category-map.txt). */
public class CategoryMap {

	public static final String RESOURCE = "/category-map.txt";
	private static final String FOODSTUFFS_NEW_WORLD = "https://www.newworld.co.nz/shop/category/%s?pg=1";
	private static final String FOODSTUFFS_PAKNSAVE = "https://www.paknsave.co.nz/shop/category/%s?pg=1";
	private static final String WOOLWORTHS = "https://www.woolworths.co.nz/shop/browse/%s";

	private final Map<String, String[]> byKey = new HashMap<>();
	private final List<String> orderedKeys = new java.util.ArrayList<>();

	public static CategoryMap empty() {
		return new CategoryMap();
	}

	public static CategoryMap loadDefault() {
		try (InputStream in = CategoryMap.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the classpath");
			}
			return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static CategoryMap parse(BufferedReader reader) throws IOException {
		CategoryMap map = new CategoryMap();
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] cols = line.split("\\|", -1);
			if (cols.length != 3) {
				throw new IllegalStateException("Bad category-map line: " + line);
			}
			if (map.byKey.put(cols[0].trim(), new String[] { cols[1].trim(), cols[2].trim() }) == null) {
				map.orderedKeys.add(cols[0].trim());
			}
		}
		return map;
	}

	public Optional<String> url(String canonicalKey, ChainId chain) {
		String[] paths = byKey.get(canonicalKey);
		if (paths == null) {
			return Optional.empty();
		}
		String path = chain == ChainId.WOOLWORTHS ? paths[1] : paths[0];
		if (path.equals("-")) {
			return Optional.empty();
		}
		return Optional.of(String.format(switch (chain) {
			case NEWWORLD -> FOODSTUFFS_NEW_WORLD;
			case PAKNSAVE -> FOODSTUFFS_PAKNSAVE;
			case WOOLWORTHS -> WOOLWORTHS;
		}, path));
	}

	/** Every distinct category page of this chain, in file order: the pages worth crawling. "-" (not sold) is skipped. */
	public List<String> urls(ChainId chain) {
		java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
		for (String key : orderedKeys) {
			url(key, chain).ifPresent(urls::add);
		}
		return List.copyOf(urls);
	}

	public int size() {
		return byKey.size();
	}
}
