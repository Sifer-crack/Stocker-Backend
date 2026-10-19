package com.stocker.pricing.refresh;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.normalize.ProductNormalizer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Routes any search term to the category page(s) it most likely lives on, by comparing the term's words
 * with the words in each category's path (leaf words count most). The supermarkets' own search pages are
 * off limits (robots.txt), so this is how an item outside the common-products list finds its page.
 * A term needs at least one leaf-word hit to be routed at all; otherwise no page is loaded.
 */
public class CategoryRouter {

	public static final String RESOURCE = "/category-index.txt";
	public static final String WOOLWORTHS_RESOURCE = "/woolworths-categories.txt";
	static final int MIN_SCORE = 3;

	private record Page(boolean woolworths, String path, Set<String> leaf, Set<String> parents, int depth) {
	}

	private final List<Page> pages = new ArrayList<>();

	public static CategoryRouter loadDefault() {
		CategoryRouter router = new CategoryRouter();
		router.load(RESOURCE, false);
		router.loadWoolworthsUrls(WOOLWORTHS_RESOURCE);
		return router;
	}

	public static CategoryRouter of(List<String> lines) {
		CategoryRouter router = new CategoryRouter();
		lines.forEach(router::add);
		return router;
	}

	private void load(String resource, boolean optional) {
		try (InputStream in = CategoryRouter.class.getResourceAsStream(resource)) {
			if (in == null) {
				if (optional) {
					return;
				}
				throw new IllegalStateException(resource + " is missing from the classpath");
			}
			new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().forEach(this::add);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Optional file with Woolworths' categories.xml contents pasted as-is (URLs separated by any whitespace);
	 * every URL containing /shop/browse/ becomes a routable Woolworths page.
	 */
	private void loadWoolworthsUrls(String resource) {
		try (InputStream in = CategoryRouter.class.getResourceAsStream(resource)) {
			if (in == null) {
				return;
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			addWoolworthsUrls(text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	void addWoolworthsUrls(String text) {
		java.util.Set<String> known = new HashSet<>();
		pages.stream().filter(Page::woolworths).forEach(page -> known.add(page.path()));
		for (String token : text.split("\\s+")) {
			int at = token.indexOf("/shop/browse/");
			if (at < 0) {
				continue;
			}
			String path = token.substring(at + "/shop/browse/".length()).replaceAll("[<>\"]|loc", "").trim();
			if (!path.isEmpty() && known.add(path)) {
				add("W|" + path);
			}
		}
	}

	private void add(String line) {
		if (line.isBlank() || line.startsWith("#")) {
			return;
		}
		boolean woolworths = line.startsWith("W|");
		String path = line.contains("|") ? line.substring(line.indexOf('|') + 1).trim() : line.trim();
		String[] segments = path.split("/");
		Set<String> leaf = slugWords(segments[segments.length - 1]);
		Set<String> parents = new HashSet<>();
		for (int i = 0; i < segments.length - 1; i++) {
			parents.addAll(slugWords(segments[i]));
		}
		pages.add(new Page(woolworths, path, leaf, parents, segments.length));
	}

	private static Set<String> slugWords(String slug) {
		return ProductNormalizer.words(slug.replace('-', ' ').replace('_', ' '));
	}

	/** Best page for the term on this chain (full URL), if any scores high enough. */
	public Optional<String> route(ChainId chain, String term, String categoryHint) {
		Set<String> termWords = ProductNormalizer.words(term);
		Set<String> hintWords = categoryHint == null ? Set.of() : ProductNormalizer.words(categoryHint);
		if (termWords.isEmpty()) {
			return Optional.empty();
		}
		boolean woolworths = chain == ChainId.WOOLWORTHS;
		return pages.stream()
				.filter(page -> page.woolworths() == woolworths)
				.map(page -> new Object[] { page, score(page, termWords, hintWords) })
				.filter(scored -> (int) scored[1] >= MIN_SCORE)
				.max(Comparator.<Object[]>comparingInt(scored -> (int) scored[1])
						.thenComparingInt(scored -> ((Page) scored[0]).depth()))
				.map(scored -> url(chain, ((Page) scored[0]).path()));
	}

	private static int score(Page page, Set<String> term, Set<String> hint) {
		int score = 0;
		for (String word : term) {
			if (page.leaf().contains(word)) {
				score += 3;
			} else if (word.length() >= 4 && page.leaf().stream().anyMatch(leaf -> leaf.length() >= 4
					&& (leaf.startsWith(word) || word.startsWith(leaf)))) {
				score += 2;
			}
			if (page.parents().contains(word)) {
				score += 1;
			}
		}
		if (score == 0) {
			return 0;
		}
		for (String word : hint) {
			if (page.leaf().contains(word) || page.parents().contains(word)) {
				score += 1;
			}
		}
		return score;
	}

	private static String url(ChainId chain, String path) {
		return switch (chain) {
			case NEWWORLD -> "https://www.newworld.co.nz/shop/category/" + path + "?pg=1";
			case PAKNSAVE -> "https://www.paknsave.co.nz/shop/category/" + path + "?pg=1";
			case WOOLWORTHS -> "https://www.woolworths.co.nz/shop/browse/" + path;
		};
	}

	public int size() {
		return pages.size();
	}
}
