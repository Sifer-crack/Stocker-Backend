package com.stocker.pricing.service.search;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.PackUnit;
import com.stocker.pricing.normalize.ParsedQuery;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.normalize.QueryParser;
import com.stocker.pricing.refresh.OnDemandRefreshService;
import com.stocker.pricing.refresh.RefreshState;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemPriceStore;
import com.stocker.pricing.service.match.NameMatcher;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Backs {@code GET /api/pricing/products} (docs/features/price-search-v2.md): the merged-product search. Works only
 * on the in-memory {@link NormalizedProductSnapshot}, so a warm request touches no database and never waits for a
 * supermarket (stale chains are refreshed in the background through {@link OnDemandRefreshService}, exactly as the
 * old match does).
 *
 * <p>One product is ONE entry however many chains list it ({@link ProductIdentity}); {@code products} are the matches
 * for the term (all sizes unless the term states a size, pack count or egg grade), {@code alternatives} are different
 * products of the same category that are not already in {@code products}. The first product's per-chain rows are
 * stored under the caller's itemId in the background (the same write path as the old match, {@link ItemPriceStore}),
 * so {@code POST /compare} keeps pricing the item; {@link #selectProduct} stores another chosen product the same way.
 * Prices are tracked per chain, never per store.
 */
@Service
public class ProductSearchService {

	private static final Logger log = LoggerFactory.getLogger(ProductSearchService.class);
	public static final int DEFAULT_LIMIT = 10;
	public static final int MAX_LIMIT = 30;
	public static final int MAX_ALTERNATIVES = 5;
	public static final String METHOD_CANONICAL = "canonical";
	public static final String METHOD_LEXICAL = "lexical";
	/** Nothing matched every word; the products below share the product-type words and ignore brand-like words and size. */
	public static final String METHOD_GENERIC = "generic-fallback";

	public static final String STATUS_FOUND = "found";
	public static final String STATUS_ALTERNATIVES_ONLY = "alternatives-only";
	public static final String STATUS_REFRESHING = "refreshing";
	public static final String STATUS_NOT_FOUND = "not-found";

	/** Words that mark a product as a variant of the plain one; a plain search ranks them after the plain product. */
	private static final Set<String> VARIANT_WORDS = Set.of("lite", "light", "trim", "skim", "skimmed", "a2",
			"organic", "lactose", "lacto", "zero", "protein", "calci", "jersey", "homogenised", "flavoured", "reduced",
			"free", "gluten", "vegan", "premium", "diet", "sugar");

	/** @param size e.g. "2L", @param packCount e.g. 12, @param grade e.g. "7"; each null when the term states none */
	public record Query(String term, List<String> words, String size, Integer packCount, String grade, String category) {
	}

	public record ChainPrice(BigDecimal price, BigDecimal unitPrice, boolean promo, String storeId,
			OffsetDateTime capturedAt, boolean stale) {
	}

	/** {@code prices} is keyed by lower-case chain id; {@code unit} is each, l or kg. */
	public record Product(String productKey, String name, String brand, String category, String packSize, String grade,
			String unit, BigDecimal unitPrice, String cheapestChainId, Map<String, ChainPrice> prices) {
	}

	/**
	 * @param status  found | alternatives-only | refreshing | not-found (see docs/features/price-search-v2.md)
	 * @param refresh per-chain state of the background refresh (lower-case chain id -> pending|done|failed|skipped);
	 *                empty when no refresh is known for this search
	 */
	public record SearchResult(Query query, String matchMethod, boolean stale, List<Product> products,
			List<Product> alternatives, String status, Map<String, String> refresh) {
	}

	public record SelectedProduct(String itemId, String productKey, List<String> chains) {
	}

	/** The product key is not (or no longer) known. */
	public static class ProductNotFoundException extends RuntimeException {
		public ProductNotFoundException(String productKey) {
			super("No product with key '" + productKey + "' is known");
		}
	}

	/** One listing of a product at a chain, scored against the term. */
	private record Scored(NormalizedProduct row, String identity, double relevance, boolean qualifierOk,
			boolean eligible, int unaskedVariants) {
	}

	/** One product: its newest listing per chain. */
	private record Group(String identity, List<Scored> listings, double relevance, boolean eligible,
			int unaskedVariants, BigDecimal cheapest) {
	}

	private final NormalizedProductSnapshot snapshot;
	private final CommonProductCatalog catalog;
	private final OnDemandRefreshService refreshService;
	private final ItemPriceStore store;
	private final NormalizedProductService normalizedService;
	private final PricingCacheProperties properties;
	private Executor persistExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "search-persist");
		thread.setDaemon(true);
		return thread;
	});

	public ProductSearchService(NormalizedProductSnapshot snapshot, CommonProductCatalog catalog,
			OnDemandRefreshService refreshService, ItemPriceStore store, NormalizedProductService normalizedService,
			PricingCacheProperties properties) {
		this.snapshot = snapshot;
		this.catalog = catalog;
		this.refreshService = refreshService;
		this.store = store;
		this.normalizedService = normalizedService;
		this.properties = properties;
	}

	/** Test seam: the default stores on a daemon background thread. */
	void setPersistExecutor(Executor persistExecutor) {
		this.persistExecutor = persistExecutor;
	}

	/**
	 * @param category optional hint, used to route an unknown term to its category page
	 * @param limit    maximum products; non-positive means the default, above {@link #MAX_LIMIT} is capped
	 * @throws IllegalArgumentException for a blank term or itemId
	 */
	public SearchResult search(String term, String itemId, String category, int limit) {
		if (!StringUtils.hasText(term) || !StringUtils.hasText(itemId)) {
			throw new IllegalArgumentException("term and itemId are required");
		}
		int max = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		return run(term.trim(), itemId, StringUtils.hasText(category) ? category.trim() : null, max, true);
	}

	private SearchResult run(String term, String itemId, String categoryHint, int max, boolean refresh) {
		PricingCacheProperties.Match config = properties.getMatch();
		OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(config.getCanonicalMaxAge());
		ParsedQuery query = QueryParser.parse(term);
		CommonProduct common = catalog.match(term).orElse(null);
		boolean stale = false;
		if (common != null && refresh) {
			// Non-blocking: checks the age of the stored prices and, for stale chains only, starts a background
			// page load; when it outlasts its deadline the callback re-runs this search (storing the new prices).
			stale = refreshService.ensureFresh(common, () -> run(term, itemId, categoryHint, max, false)).stale();
		}
		Pool pool = common == null ? null : canonicalPool(common, query, since, config);
		if (pool == null) {
			pool = lexicalPool(common, query, since, config);
			if (refresh) {
				Set<ChainId> needed = chainsNeedingRefresh(pool);
				if (!needed.isEmpty() && refreshService.ensureForTerm(term, categoryHint, needed,
						() -> run(term, itemId, categoryHint, max, false)).refreshed()) {
					pool = lexicalPool(common, query, since, config);
				}
			}
		}
		SearchResult result = assemble(term, query, common, categoryHint, pool, max, stale);
		if (result.products().isEmpty() && (result.alternatives().isEmpty() || METHOD_LEXICAL.equals(pool.method))) {
			// nothing matched every word: the products that share the product-type words beat partial word overlaps
			Pool generic = genericPool(common, query, since);
			if (generic != null) {
				result = assemble(term, query, common, categoryHint, generic, max, stale);
			}
		}
		result = withStatus(result, progress(common, term));
		if (result.products().isEmpty() && result.alternatives().isEmpty()) {
			persistExecutor.execute(() -> {
				try {
					normalizedService.recordUnmatched(term);
				}
				catch (RuntimeException e) {
					log.warn("Could not record the unmatched term '{}': {}", term, e.getMessage());
				}
			});
		}
		if (!result.products().isEmpty()) {
			Group first = pool.matchesFirst(max).get(0);
			List<NormalizedProduct> rows = first.listings().stream().map(Scored::row).toList();
			persistExecutor.execute(() -> {
				try {
					store.store(itemId, rows);
				}
				catch (RuntimeException e) {
					log.warn("Could not store the matched prices under item {}: {}", itemId, e.getMessage());
				}
			});
		}
		return result;
	}

	/**
	 * Stores the product with this key under the item id so that compare prices it. The key is matched by identity,
	 * so a key whose words are in another order, or a key from an earlier search, still finds the product.
	 *
	 * @throws ProductNotFoundException when no stored product has the key
	 * @throws IllegalArgumentException for a blank itemId or productKey
	 */
	public SelectedProduct selectProduct(String itemId, String productKey) {
		if (!StringUtils.hasText(itemId) || !StringUtils.hasText(productKey)) {
			throw new IllegalArgumentException("itemId and productKey are required");
		}
		String wanted = ProductIdentity.canonicalOfKey(productKey);
		OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getMatch().getCanonicalMaxAge());
		List<NormalizedProduct> listings = newestPerChain(snapshot.findByCapturedAtAfter(since).stream()
				.filter(ProductSearchService::usable)
				.filter(row -> ProductIdentity.canonical(row).equals(wanted)).toList());
		if (listings.isEmpty()) {
			throw new ProductNotFoundException(productKey);
		}
		store.replace(itemId, listings);
		return new SelectedProduct(itemId, productKey,
				listings.stream().map(row -> row.getChainId().toLowerCase(Locale.ROOT)).sorted().toList());
	}

	// ---- candidate pools -------------------------------------------------------------------------------------------

	/** The scored groups of one search: {@code all} in no particular order. */
	private final class Pool {
		final String method;
		final List<Group> groups;
		final Comparator<Group> matchOrder = Comparator.comparingDouble(Group::relevance).reversed()
				.thenComparingInt(Group::unaskedVariants)
				.thenComparing(group -> unitPriceOf(group), Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(Group::cheapest);

		Pool(String method, List<Group> groups) {
			this.method = method;
			this.groups = groups;
		}

		List<Group> matchesFirst(int max) {
			return groups.stream().filter(Group::eligible).sorted(matchOrder).limit(max).toList();
		}
	}

	/** A common product: its indexed rows are the matches; sibling products of the same shelf are the alternatives. */
	private Pool canonicalPool(CommonProduct common, ParsedQuery query, OffsetDateTime since,
			PricingCacheProperties.Match config) {
		Set<String> termWords = query.words();
		List<Scored> scored = new ArrayList<>();
		boolean anyOwn = false;
		for (CommonProduct sibling : catalog.all()) {
			if (!sibling.key().equals(common.key()) && !sibling.keyword().equals(common.keyword())) {
				continue;
			}
			boolean own = sibling.key().equals(common.key());
			for (NormalizedProduct row : snapshot.findByCanonicalKeyAndCapturedAtAfter(sibling.key(), since)) {
				if (!usable(row) || !catalog.accepts(sibling, row.getDisplayName(), termWords)) {
					continue;
				}
				Set<String> nameWords = ProductNormalizer.words(row.getDisplayName());
				long shared = termWords.stream().filter(nameWords::contains).count();
				double coverage = termWords.isEmpty() ? 0.0 : (double) shared / termWords.size();
				boolean qualifierOk = NameMatcher.qualifiersMatch(query, row.getDisplayName(), ProductIdentity.packOf(row));
				int variants = (int) nameWords.stream()
						.filter(word -> VARIANT_WORDS.contains(word) && !termWords.contains(word)).count();
				boolean eligible = own && qualifierOk && (termWords.isEmpty() || shared > 0);
				anyOwn |= eligible || (own && shared > 0);
				scored.add(new Scored(row, ProductIdentity.canonical(row), coverage, qualifierOk, eligible, variants));
			}
		}
		return anyOwn ? new Pool(METHOD_CANONICAL, group(scored)) : null;
	}

	/** Any other term (or a common product with nothing indexed): every stored product scored by word overlap. */
	private Pool lexicalPool(CommonProduct common, ParsedQuery query, OffsetDateTime since,
			PricingCacheProperties.Match config) {
		Set<String> queryWords = query.words();
		List<Scored> scored = new ArrayList<>();
		for (NormalizedProduct row : snapshot.findByCapturedAtAfter(since)) {
			if (!usable(row)) {
				continue;
			}
			String name = row.getDisplayName();
			double words = NameMatcher.wordScore(query, name);
			if (common != null) {
				if (!catalog.accepts(common, name, queryWords)) {
					continue;
				}
				if (catalog.match(name).map(CommonProduct::key).filter(common.key()::equals).isPresent()) {
					words = Math.max(words, config.getMinScore());
				}
			}
			else if (CommonProductCatalog.looksLikeDish(ProductNormalizer.wordList(name), queryWords, queryWords)) {
				continue;
			}
			if (words == 0.0) {
				continue;
			}
			boolean qualifierOk = NameMatcher.qualifiersMatch(query, name, ProductIdentity.packOf(row));
			double relevance = qualifierOk ? words : Math.min(words, 0.4);
			if (relevance < config.getAlternativeMinScore()) {
				continue;
			}
			scored.add(new Scored(row, ProductIdentity.canonical(row), relevance, qualifierOk,
					qualifierOk && relevance >= config.getMinScore(), 0));
		}
		return new Pool(METHOD_LEXICAL, group(scored));
	}

	/**
	 * Nothing matched every query word: drop the words no stored product has (a brand nobody lists) and the size, then
	 * the leading modifier words one by one (never below the last two words), and offer the products that contain all
	 * the words left. They are alternatives only (never {@code products}) and never dishes or condiments made with the
	 * product ("Rice Wine Vinegar" is not wine).
	 */
	private Pool genericPool(CommonProduct common, ParsedQuery query, OffsetDateTime since) {
		Map<NormalizedProduct, Set<String>> wordsOf = new LinkedHashMap<>();
		Set<String> vocabulary = new LinkedHashSet<>();
		for (NormalizedProduct row : snapshot.findByCapturedAtAfter(since)) {
			if (usable(row)) {
				Set<String> words = ProductNormalizer.words(row.getDisplayName());
				wordsOf.put(row, words);
				vocabulary.addAll(words);
			}
		}
		List<String> known = query.words().stream().filter(vocabulary::contains).toList();
		if (known.isEmpty()) {
			return null;
		}
		int floor = Math.min(2, known.size());
		for (int from = 0; known.size() - from >= floor; from++) {
			List<String> tokens = known.subList(from, known.size());
			Set<String> tokenSet = new LinkedHashSet<>(tokens);
			List<Scored> scored = new ArrayList<>();
			for (Map.Entry<NormalizedProduct, Set<String>> entry : wordsOf.entrySet()) {
				NormalizedProduct row = entry.getKey();
				if (!entry.getValue().containsAll(tokenSet)) {
					continue;
				}
				String name = row.getDisplayName();
				if (followedByDishWord(ProductNormalizer.wordList(name), tokenSet, query.words())
						|| (common != null && !catalog.accepts(common, name, query.words()))) {
					continue;
				}
				double relevance = NameMatcher.wordScore(
						new ParsedQuery(String.join(" ", tokens), tokenSet, tokens, null, null, null), name);
				scored.add(new Scored(row, ProductIdentity.canonical(row), Math.max(relevance, 0.01), false, false, 0));
			}
			if (!scored.isEmpty()) {
				return new Pool(METHOD_GENERIC, group(scored));
			}
		}
		return null;
	}

	/** A dish or condiment word AFTER the last product-type word ("Rice Wine Vinegar"); "Cooking Rice Wine" is fine. */
	private static boolean followedByDishWord(List<String> productWords, Set<String> tokens, Set<String> asked) {
		int last = -1;
		for (int i = 0; i < productWords.size(); i++) {
			if (tokens.contains(productWords.get(i))) {
				last = i;
			}
		}
		for (int i = last + 1; i < productWords.size(); i++) {
			String word = productWords.get(i);
			if (CommonProductCatalog.DISH_WORDS.contains(word) && !asked.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/** found / alternatives-only / refreshing / not-found, plus the per-chain refresh states. */
	private SearchResult withStatus(SearchResult result, Map<ChainId, RefreshState> progress) {
		Map<String, String> refresh = new TreeMap<>();
		progress.forEach((chain, state) -> refresh.put(chain.name().toLowerCase(Locale.ROOT), state.wire()));
		boolean pending = progress.containsValue(RefreshState.PENDING);
		String status = !result.products().isEmpty() ? STATUS_FOUND
				: pending ? STATUS_REFRESHING
				: !result.alternatives().isEmpty() ? STATUS_ALTERNATIVES_ONLY
				: STATUS_NOT_FOUND;
		return new SearchResult(result.query(), result.matchMethod(), result.stale(), result.products(),
				result.alternatives(), status, Map.copyOf(refresh));
	}

	private Map<ChainId, RefreshState> progress(CommonProduct common, String term) {
		Map<ChainId, RefreshState> merged = new java.util.EnumMap<>(ChainId.class);
		if (common != null) {
			merged.putAll(refreshService.progressForCommon(common));
		}
		refreshService.progressForTerm(term).forEach((chain, state) -> merged.merge(chain, state,
				(a, b) -> a == RefreshState.PENDING || b == RefreshState.PENDING ? RefreshState.PENDING
						: a.compareTo(b) <= 0 ? a : b));
		return merged;
	}

	/** Chains without an eligible product, or whose product is old: they get the term's one category page loaded. */
	private Set<ChainId> chainsNeedingRefresh(Pool pool) {
		Set<ChainId> needed = new LinkedHashSet<>(List.of(ChainId.values()));
		OffsetDateTime staleBefore = staleBefore();
		Set<ChainId> old = new LinkedHashSet<>();
		for (Group group : pool.groups) {
			if (!group.eligible()) {
				continue;
			}
			for (Scored listing : group.listings()) {
				ChainId chain = chainOf(listing.row().getChainId());
				needed.remove(chain);
				if (listing.row().getCapturedAt().isBefore(staleBefore)) {
					old.add(chain);
				}
			}
		}
		needed.addAll(old);
		return needed;
	}

	// ---- assembling the answer -------------------------------------------------------------------------------------

	private SearchResult assemble(String term, ParsedQuery query, CommonProduct common, String categoryHint, Pool pool,
			int max, boolean refreshStale) {
		List<Group> matches = pool.matchesFirst(max);
		Set<String> matchIdentities = new LinkedHashSet<>();
		matches.forEach(group -> matchIdentities.add(group.identity()));
		BigDecimal reference = matches.isEmpty() ? null : matches.get(0).cheapest();
		Comparator<Group> closeness = reference == null
				? Comparator.comparing(Group::cheapest)
				: Comparator.comparing(group -> group.cheapest().subtract(reference).abs());
		List<Group> alternatives = pool.groups.stream()
				.filter(group -> !matchIdentities.contains(group.identity()))
				.sorted(Comparator.comparingDouble(Group::relevance).reversed().thenComparing(closeness))
				.limit(MAX_ALTERNATIVES).toList();
		OffsetDateTime staleBefore = staleBefore();
		List<Product> products = matches.stream().map(group -> toProduct(group, staleBefore)).toList();
		boolean stale = refreshStale || products.stream().anyMatch(
				product -> product.prices().values().stream().anyMatch(ChainPrice::stale));
		String category = common != null ? common.key() : categoryHint;
		return new SearchResult(
				new Query(term, List.copyOf(query.words()), query.measure() == null ? null : query.measure().label(),
						query.count() == null ? null : query.count().quantity().intValue(),
						query.eggGrade() == null ? null : query.eggGrade().toString(), category),
				pool.method, stale, products, alternatives.stream().map(group -> toProduct(group, staleBefore)).toList(),
				STATUS_NOT_FOUND, Map.of());
	}

	private Product toProduct(Group group, OffsetDateTime staleBefore) {
		List<Scored> listings = new ArrayList<>(group.listings());
		// representative listing: one that names the brand first, then by chain id, so the key does not flap
		Scored representative = listings.stream()
				.sorted(Comparator.comparing((Scored s) -> ProductIdentity.brandOf(s.row()) == null)
						.thenComparing(s -> s.row().getChainId()))
				.findFirst().orElseThrow();
		NormalizedProduct row = representative.row();
		PackSize pack = ProductIdentity.packOf(row);
		Map<String, ChainPrice> prices = new TreeMap<>();
		Scored cheapest = null;
		for (Scored listing : listings) {
			NormalizedProduct r = listing.row();
			prices.put(r.getChainId().toLowerCase(Locale.ROOT), new ChainPrice(r.getPriceAmount(),
					pack == null ? null : pack.unitPrice(r.getPriceAmount()), r.isPromoFlag(), r.getStoreId(),
					r.getCapturedAt(), r.getCapturedAt().isBefore(staleBefore)));
			if (cheapest == null || r.getPriceAmount().compareTo(cheapest.row().getPriceAmount()) < 0) {
				cheapest = listing;
			}
		}
		String brand = listings.stream().map(s -> ProductIdentity.brandOf(s.row())).filter(b -> b != null).findFirst()
				.orElse(null);
		String category = row.getCanonicalKey();
		return new Product(ProductIdentity.displayKey(row), row.getDisplayName(), brand,
				category == null ? null : catalog.byKey(category).map(CommonProduct::key).orElse(null),
				pack == null ? null : pack.label(), ProductIdentity.gradeOf(row), unitOf(pack),
				pack == null ? null : pack.unitPrice(cheapest.row().getPriceAmount()),
				cheapest.row().getChainId().toLowerCase(Locale.ROOT), prices);
	}

	private static String unitOf(PackSize pack) {
		if (pack == null) {
			return null;
		}
		return switch (pack.unit()) {
			case ML -> "l";
			case G -> "kg";
			case EACH -> "each";
		};
	}

	private static BigDecimal unitPriceOf(Group group) {
		NormalizedProduct row = group.listings().get(0).row();
		PackSize pack = ProductIdentity.packOf(row);
		return pack == null ? null : pack.unitPrice(group.cheapest());
	}

	// ---- shared helpers --------------------------------------------------------------------------------------------

	/** One group per distinct product, holding its newest listing per chain. */
	private List<Group> group(List<Scored> scored) {
		Map<String, Map<String, Scored>> byIdentity = new LinkedHashMap<>();
		for (Scored candidate : scored) {
			Map<String, Scored> byChain = byIdentity.computeIfAbsent(candidate.identity(), id -> new LinkedHashMap<>());
			byChain.merge(candidate.row().getChainId().toLowerCase(Locale.ROOT), candidate,
					(existing, other) -> other.row().getCapturedAt().isAfter(existing.row().getCapturedAt()) ? other : existing);
		}
		List<Group> groups = new ArrayList<>();
		byIdentity.forEach((identity, byChain) -> {
			List<Scored> listings = List.copyOf(byChain.values());
			groups.add(new Group(identity, listings,
					listings.stream().mapToDouble(Scored::relevance).max().orElse(0.0),
					listings.stream().anyMatch(Scored::eligible),
					listings.stream().mapToInt(Scored::unaskedVariants).min().orElse(0),
					listings.stream().map(s -> s.row().getPriceAmount()).min(Comparator.naturalOrder()).orElseThrow()));
		});
		return groups;
	}

	private static List<NormalizedProduct> newestPerChain(List<NormalizedProduct> rows) {
		Map<String, NormalizedProduct> newest = new LinkedHashMap<>();
		for (NormalizedProduct row : rows) {
			newest.merge(row.getChainId().toLowerCase(Locale.ROOT), row,
					(existing, other) -> other.getCapturedAt().isAfter(existing.getCapturedAt()) ? other : existing);
		}
		return List.copyOf(newest.values());
	}

	private OffsetDateTime staleBefore() {
		return OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getOnDemand().getStaleAfter());
	}

	private static boolean usable(NormalizedProduct row) {
		return StringUtils.hasText(row.getDisplayName()) && row.getPriceAmount() != null
				&& row.getPriceAmount().signum() > 0 && row.getCapturedAt() != null && chainOrNull(row.getChainId()) != null;
	}

	private static ChainId chainOf(String chainId) {
		return ChainId.valueOf(chainId.toUpperCase(Locale.ROOT));
	}

	private static ChainId chainOrNull(String chainId) {
		try {
			return chainId == null ? null : chainOf(chainId);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}
}
