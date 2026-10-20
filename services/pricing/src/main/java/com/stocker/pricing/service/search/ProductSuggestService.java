package com.stocker.pricing.service.search;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSizeParser;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.normalize.QueryParser;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Search-bar suggestions that reflect what the supermarkets actually sell (docs/features/price-search-v2.md,
 * "Suggestions"). Reads only the in-memory {@link NormalizedProductSnapshot}: no database when warm and never a
 * supermarket request.
 *
 * <p>The query is split into words; every word but the last must be a whole word of the product, the last is a prefix
 * (typing as you go). Suggestions: first a few key-level generic ones from the common-products list ("Eggs", "Cooking
 * wine", "Red wine"), one per canonical key and alias, then specific products (brand + type, size removed). Both are
 * ranked by the number of chains that carry them, then the number of distinct products, then the shorter text.
 */
@Service
public class ProductSuggestService {

	static final int DEFAULT_LIMIT = 8;
	static final int MAX_LIMIT = 20;
	private static final int ALIASES_PER_KEY = 2;
	private static final int CACHE_LIMIT = 100_000;
	private static final String[] NO_WORDS = {};

	public record Suggestion(String text, String canonicalKey, int chainCount, List<String> chains, String sampleSize,
			int productCount) {
	}

	/** What a stored name looks like as a suggestion; a pure function of the name, remembered. */
	private record Cleaned(String text, String wordKey, String size, Set<String> tokens, Set<String> words) {
	}

	private static final Map<String, Cleaned> CLEANED = new ConcurrentHashMap<>();

	private record Candidate(CommonProduct product, String text, Set<String> tokens, Set<String> words, boolean isName) {
	}

	/** Chains, distinct products and sizes behind one suggestion. */
	private static final class Stock {
		final Set<String> chains = new TreeSet<>();
		final Set<String> variants = new HashSet<>();
		final Map<String, Integer> sizes = new HashMap<>();
		String key;
		String text;

		void add(NormalizedProduct row, Cleaned cleaned) {
			chains.add(row.getChainId().toLowerCase(Locale.ROOT));
			if (variants.add(cleaned.wordKey() + "|" + cleaned.size())) {
				if (cleaned.size() != null) {
					sizes.merge(cleaned.size(), 1, Integer::sum);
				}
			}
			if (text == null || cleaned.text().length() < text.length()
					|| (cleaned.text().length() == text.length() && cleaned.text().compareTo(text) < 0)) {
				text = cleaned.text();
			}
		}

		String sampleSize() {
			return sizes.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
					.thenComparing(Map.Entry.<String, Integer>comparingByKey().reversed())).map(Map.Entry::getKey).orElse(null);
		}

		Suggestion toSuggestion(String displayText, String canonicalKey) {
			return new Suggestion(displayText, canonicalKey, chains.size(), List.copyOf(chains), sampleSize(), variants.size());
		}
	}

	private static final Comparator<Suggestion> RANK = Comparator.comparingInt(Suggestion::chainCount).reversed()
			.thenComparing(Comparator.comparingInt(Suggestion::productCount).reversed())
			.thenComparingInt((Suggestion s) -> s.text().length())
			.thenComparing(Suggestion::text);

	private final NormalizedProductSnapshot snapshot;
	private final CommonProductCatalog catalog;
	private final PricingCacheProperties properties;
	private final List<Candidate> candidates;

	public ProductSuggestService(NormalizedProductSnapshot snapshot, CommonProductCatalog catalog,
			PricingCacheProperties properties) {
		this.snapshot = snapshot;
		this.catalog = catalog;
		this.properties = properties;
		this.candidates = buildCandidates(catalog);
	}

	/** Up to {@code limit} suggestions (default 8, at most 20) for what has been typed so far. */
	public List<Suggestion> suggest(String q, int limit) {
		int max = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		String[] typed = tokens(q);
		OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getMatch().getCanonicalMaxAge());
		List<NormalizedProduct> rows = new ArrayList<>();
		Map<String, List<NormalizedProduct>> byKey = new HashMap<>();
		for (NormalizedProduct row : snapshot.findByCapturedAtAfter(since)) {
			if (usable(row)) {
				rows.add(row);
				if (row.getCanonicalKey() != null) {
					byKey.computeIfAbsent(row.getCanonicalKey(), key -> new ArrayList<>()).add(row);
				}
			}
		}
		if (typed.length == 0) {
			return popular(byKey, max);
		}
		String last = typed[typed.length - 1];
		String[] before = java.util.Arrays.copyOf(typed, typed.length - 1);

		List<Suggestion> names = new ArrayList<>();
		List<Suggestion> aliases = new ArrayList<>();
		Set<String> covered = new HashSet<>();
		Map<String, Integer> perKey = new HashMap<>();
		Set<String> seen = new HashSet<>();
		List<Suggestion> aliasPool = new ArrayList<>();
		Map<Suggestion, String> aliasKey = new HashMap<>();
		for (Candidate candidate : candidates) {
			List<NormalizedProduct> keyRows = byKey.get(candidate.product().key());
			if (keyRows == null || !matches(candidate.tokens(), before, last)
					|| !seen.add(candidate.product().key() + "|" + String.join(" ", new TreeSet<>(candidate.words())))) {
				continue;
			}
			Stock stock = new Stock();
			for (NormalizedProduct row : keyRows) {
				Cleaned cleaned = cleaned(row.getDisplayName());
				if (candidate.isName() || cleaned.words().containsAll(candidate.words())) {
					stock.add(row, cleaned);
				}
			}
			if (stock.chains.isEmpty()) {
				continue;
			}
			covered.add(candidate.product().key());
			Suggestion suggestion = stock.toSuggestion(candidate.text(), candidate.product().key());
			if (candidate.isName()) {
				names.add(suggestion);
			}
			else {
				aliasPool.add(suggestion);
			}
		}
		names.sort(RANK);
		aliasPool.sort(RANK);
		for (Suggestion alias : aliasPool) {
			if (perKey.merge(alias.canonicalKey(), 1, Integer::sum) <= ALIASES_PER_KEY) {
				aliases.add(alias);
			}
		}
		int genericCap = Math.max(2, Math.min(5, max * 5 / 8));
		List<Suggestion> result = new ArrayList<>();
		Set<String> texts = new HashSet<>();
		for (List<Suggestion> group : List.of(names, aliases)) {
			for (Suggestion suggestion : group) {
				if (result.size() < genericCap && texts.add(suggestion.text().toLowerCase(Locale.ROOT))) {
					result.add(suggestion);
				}
			}
		}

		Map<String, Stock> groups = new LinkedHashMap<>();
		for (NormalizedProduct row : rows) {
			if (row.getCanonicalKey() != null && covered.contains(row.getCanonicalKey())) {
				continue;   // folded into the key-level suggestion above
			}
			Cleaned cleaned = cleaned(row.getDisplayName());
			if (cleaned.wordKey().isEmpty() || !matches(cleaned.tokens(), before, last)) {
				continue;
			}
			Stock stock = groups.computeIfAbsent(cleaned.wordKey(), key -> new Stock());
			stock.add(row, cleaned);
			if (stock.key == null) {
				stock.key = row.getCanonicalKey();
			}
		}
		List<Suggestion> specifics = new ArrayList<>();
		for (Stock stock : groups.values()) {
			specifics.add(stock.toSuggestion(stock.text, stock.key));
		}
		specifics.sort(RANK);
		for (Suggestion suggestion : specifics) {
			if (result.size() >= max) {
				break;
			}
			if (texts.add(suggestion.text().toLowerCase(Locale.ROOT))) {
				result.add(suggestion);
			}
		}
		return result.size() > max ? new ArrayList<>(result.subList(0, max)) : result;
	}

	/** Empty or one-letter query: the key-level products stocked at the most chains. */
	private List<Suggestion> popular(Map<String, List<NormalizedProduct>> byKey, int max) {
		List<Suggestion> all = new ArrayList<>();
		for (Candidate candidate : candidates) {
			List<NormalizedProduct> keyRows = byKey.get(candidate.product().key());
			if (keyRows == null || !candidate.isName()) {
				continue;
			}
			Stock stock = new Stock();
			keyRows.forEach(row -> stock.add(row, cleaned(row.getDisplayName())));
			all.add(stock.toSuggestion(candidate.text(), candidate.product().key()));
		}
		all.sort(RANK);
		return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
	}

	/** Whole-word match for every earlier word (as typed or in its singular form), prefix match for the last. */
	private static boolean matches(Set<String> tokens, String[] before, String last) {
		for (String word : before) {
			if (!tokens.contains(word) && !tokens.containsAll(ProductNormalizer.wordList(word))) {
				return false;
			}
		}
		for (String token : tokens) {
			if (token.startsWith(last)) {
				return true;
			}
		}
		return false;
	}

	/** Lower-case words of the query; a one-letter query (or only digits) counts as nothing typed yet. */
	private static String[] tokens(String q) {
		if (q == null || q.trim().length() < 2) {
			return NO_WORDS;
		}
		List<String> out = new ArrayList<>();
		for (String raw : q.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").split("[^\\p{L}\\p{N}]+")) {
			if (!raw.isEmpty()) {
				out.add(raw);
			}
		}
		if (!out.isEmpty() && out.get(out.size() - 1).chars().allMatch(Character::isDigit)) {
			out.remove(out.size() - 1);   // "milk 2" is still "milk": sizes are never part of a suggestion
		}
		return out.toArray(String[]::new);
	}

	private static Cleaned cleaned(String displayName) {
		if (CLEANED.size() > CACHE_LIMIT) {
			CLEANED.clear();
		}
		return CLEANED.computeIfAbsent(displayName, ProductSuggestService::clean);
	}

	private static Cleaned clean(String name) {
		String text = QueryParser.stripGradeKeepCase(PackSizeParser.stripKeepCase(name));
		text = text.replaceAll("\\s+", " ").replaceAll("^[\\s\\-,/&+.]+|[\\s\\-,/&+.]+$", "");
		if (text.equals(text.toUpperCase(Locale.ROOT)) || text.equals(text.toLowerCase(Locale.ROOT))) {
			text = titleCase(text);
		}
		List<String> stemmed = ProductNormalizer.wordList(text);
		Set<String> words = new HashSet<>(stemmed);
		Set<String> tokens = new HashSet<>(words);
		for (String raw : text.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").split("[^\\p{L}\\p{N}]+")) {
			if (!raw.isEmpty()) {
				tokens.add(raw);
			}
		}
		String wordKey = String.join(" ", new TreeSet<>(stemmed));
		String size = PackSizeParser.parse(name).map(com.stocker.pricing.normalize.PackSize::label).orElse(null);
		return new Cleaned(text, wordKey, size, tokens, words);
	}

	private static String titleCase(String text) {
		StringBuilder out = new StringBuilder(text.length());
		boolean start = true;
		for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append(start ? Character.toUpperCase(c) : c);
			start = c == ' ' || c == '-';
		}
		return out.toString();
	}

	private static List<Candidate> buildCandidates(CommonProductCatalog catalog) {
		List<Candidate> list = new ArrayList<>();
		for (CommonProduct product : catalog.all()) {
			list.add(candidate(product, product.name(), true));
			for (String alias : catalog.aliasTexts(product.key())) {
				list.add(candidate(product, capitalise(alias), false));
			}
		}
		return List.copyOf(list);
	}

	private static Candidate candidate(CommonProduct product, String text, boolean isName) {
		Set<String> words = new HashSet<>(ProductNormalizer.wordList(text));
		Set<String> tokens = new HashSet<>(words);
		for (String raw : text.toLowerCase(Locale.ROOT).replace("'", "").split("[^\\p{L}\\p{N}]+")) {
			if (!raw.isEmpty()) {
				tokens.add(raw);
			}
		}
		return new Candidate(product, text, tokens, words, isName);
	}

	private static String capitalise(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static boolean usable(NormalizedProduct row) {
		return row.getDisplayName() != null && !row.getDisplayName().isBlank() && row.getPriceAmount() != null
				&& row.getPriceAmount().signum() > 0 && row.getCapturedAt() != null && row.getChainId() != null
				&& !row.getChainId().isBlank();
	}
}
