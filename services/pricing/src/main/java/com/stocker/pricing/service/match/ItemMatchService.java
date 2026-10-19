package com.stocker.pricing.service.match;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.PackUnit;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.refresh.OnDemandRefreshService;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Backs {@code GET /api/pricing/match}: finds, per supermarket chain, the product that best matches
 * a requested item among the products the scheduled ingest has already scraped, plus a few
 * alternatives (other products that share the item's words). Stored data is used first; when
 * nothing matches at all, one background ingest run is started (rate-limited) and the caller is
 * expected to ask again shortly - an empty result means "not found yet", never an error.
 *
 * <p>The chosen products - and only those - are also written under the caller's itemId through the
 * usual write path (price_records, price_stats, PriceRecordCaptured), so that
 * {@code CompareShoppingList} for that itemId prices exactly the matched products.
 */
@Service
public class ItemMatchService {

	private static final Logger log = LoggerFactory.getLogger(ItemMatchService.class);
	public static final String METHOD_LEXICAL = "lexical";
	/** Matched through the common-products list and the normalised product index. */
	public static final String METHOD_CANONICAL = "canonical";
	public record MatchedProduct(String chainId, String storeId, String productName, String brand,
			BigDecimal priceAmount, String currency, boolean promoFlag, OffsetDateTime capturedAt, double score,
			BigDecimal unitPrice, String packSize) {
	}

	/** {@code stale}: some chain's prices are older than the freshness threshold (a refresh may still be running). */
	public record MatchResult(String matchMethod, List<MatchedProduct> matches, List<MatchedProduct> alternatives,
			boolean stale) {
	}

	private record Candidate(NormalizedProduct row, String name, double score) {
	}

	private final PriceRecordRepository repository;
	private final NormalizedProductSnapshot snapshot;
	private final NormalizedProductService normalizedService;
	private final CommonProductCatalog catalog;
	private final OnDemandRefreshService refreshService;
	private final PriceStatsService priceStatsService;
	private final EventPublisher eventPublisher;
	private final PricingCacheProperties properties;
	private final ObjectProvider<IngestScheduler> ingestScheduler;

	private final ExecutorService ingestExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "match-ingest");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean ingestRunning = new AtomicBoolean(false);
	private Executor persistExecutor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "match-persist");
		thread.setDaemon(true);
		return thread;
	});
	private volatile Instant lastIngestTrigger = Instant.EPOCH;

	public ItemMatchService(PriceRecordRepository repository, NormalizedProductSnapshot snapshot,
			NormalizedProductService normalizedService, CommonProductCatalog catalog,
			OnDemandRefreshService refreshService, PriceStatsService priceStatsService, EventPublisher eventPublisher, PricingCacheProperties properties,
			ObjectProvider<IngestScheduler> ingestScheduler) {
		this.repository = repository;
		this.snapshot = snapshot;
		this.normalizedService = normalizedService;
		this.catalog = catalog;
		this.refreshService = refreshService;
		this.priceStatsService = priceStatsService;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
		this.ingestScheduler = ingestScheduler;
	}

	public MatchResult match(String term, String itemId) {
		return match(term, itemId, null, null);
	}

	/**
	 * @param weightKg optional known pack weight (e.g. a pantry product's selling weight)
	 * @param volumeL  optional known pack volume
	 */
	public MatchResult match(String term, String itemId, BigDecimal weightKg, BigDecimal volumeL) {
		return match(term, itemId, weightKg, volumeL, null);
	}

	/** @param category optional hint (e.g. the pantry product's grocery type) used to route an unknown item to its page */
	public MatchResult match(String term, String itemId, BigDecimal weightKg, BigDecimal volumeL, String category) {
		return match(term, itemId, weightKg, volumeL, category, true);
	}

	/**
	 * Like {@link #match(String, String, BigDecimal, BigDecimal, String)} but only reads what is already stored: it
	 * never starts or waits for a live refresh. This is what comparing a shopping list uses.
	 */
	public MatchResult matchStored(String term, String itemId, BigDecimal weightKg, BigDecimal volumeL, String category) {
		return match(term, itemId, weightKg, volumeL, category, false);
	}

	private MatchResult match(String term, String itemId, BigDecimal weightKg, BigDecimal volumeL, String category,
			boolean refresh) {
		PricingCacheProperties.Match config = properties.getMatch();
		OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(config.getMaxAge());
		PackSize requested = PackSize.ofWeightKg(weightKg);
		if (requested == null) {
			requested = PackSize.ofVolumeL(volumeL);
		}
		if (requested == null) {
			requested = ProductNormalizer.sizeOf(term);
		}
		Optional<CommonProduct> common = catalog.match(term);
		if (common.isPresent()) {
			boolean stale = false;
			if (refresh) {
				// Check the age of the stored prices first and, for stale chains only, fetch just the page
				// this product lives on. A refresh that outlasts its deadline re-runs the match afterwards
				// (without refreshing again) so the fresh prices are stored under itemId and announced.
				OnDemandRefreshService.Outcome outcome = refreshService.ensureFresh(common.get(),
						() -> match(term, itemId, weightKg, volumeL, category, false));
				stale = outcome.stale();
			}
			MatchResult canonical = matchCanonical(term, itemId, common.get(), requested,
					OffsetDateTime.now(ZoneOffset.UTC).minus(config.getCanonicalMaxAge()), config, stale);
			if (canonical != null) {
				return canonical;
			}
		}
		// Any other item (or a common product with nothing indexed): score everything stored against the term.
		OffsetDateTime lexicalSince = OffsetDateTime.now(ZoneOffset.UTC).minus(config.getCanonicalMaxAge());
		PricingCacheProperties.OnDemand onDemand = properties.getOnDemand();
		Lexical lexical = lexical(term, lexicalSince, config, onDemand);
		if (refresh) {
			// Chains with no good match, or only an old one, get the one category page this term is routed to.
			Set<ChainId> needed = new java.util.LinkedHashSet<>(lexical.missing());
			needed.addAll(lexical.staleChains());
			if (!needed.isEmpty()) {
				OnDemandRefreshService.Outcome outcome = refreshService.ensureForTerm(term, category, needed,
						() -> match(term, itemId, weightKg, volumeL, category, false));
				if (outcome.refreshed()) {
					lexical = lexical(term, lexicalSince, config, onDemand);
				}
			}
		}

		List<Candidate> chosen = new ArrayList<>();
		for (Lex lex : lexical.chosen()) {
			chosen.add(new Candidate(lex.row(), lex.row().getDisplayName(), lex.score()));
		}
		List<MatchedProduct> matches = lexical.chosen().stream().map(ItemMatchService::toProduct)
				.sorted(Comparator.comparing(MatchedProduct::priceAmount)).toList();
		List<MatchedProduct> alternatives = new ArrayList<>(lexical.alternatives().stream().map(ItemMatchService::toProduct).toList());
		if (matches.isEmpty() && alternatives.isEmpty()) {
			// a database write: not while the user waits
			persistExecutor.execute(() -> normalizedService.recordUnmatched(term));
			if (config.isOnDemandIngest()) {
				triggerIngest();
			}
		}
		persistUnderItemId(itemId, chosen);
		alternatives.sort(Comparator.comparingDouble(MatchedProduct::score).reversed()
				.thenComparing(MatchedProduct::priceAmount));
		return new MatchResult(METHOD_LEXICAL, matches, List.copyOf(alternatives), lexical.stale());
	}

	private record Lex(NormalizedProduct row, double score) {
	}

	/** {@code missing}: chains with no good match; {@code staleChains}: chains whose chosen match is old. */
	private record Lexical(List<Lex> chosen, List<Lex> alternatives, Set<ChainId> missing, Set<ChainId> staleChains,
			boolean stale) {
	}

	/** Every stored product (one per distinct product per chain), scored against the request by word overlap. */
	private Lexical lexical(String term, OffsetDateTime since, PricingCacheProperties.Match config,
			PricingCacheProperties.OnDemand onDemand) {
		Map<String, List<Lex>> byChain = new LinkedHashMap<>();
		for (NormalizedProduct row : dedupe(snapshot.findByCapturedAtAfter(since))) {
			if (!StringUtils.hasText(row.getDisplayName()) || !knownChain(row.getChainId())
					|| row.getPriceAmount() == null || row.getPriceAmount().signum() <= 0) {
				continue;
			}
			double score = NameMatcher.score(term, row.getDisplayName());
			if (score >= config.getAlternativeMinScore()) {
				byChain.computeIfAbsent(row.getChainId(), chain -> new ArrayList<>()).add(new Lex(row, score));
			}
		}
		OffsetDateTime staleBefore = OffsetDateTime.now(ZoneOffset.UTC).minus(onDemand.getStaleAfter());
		List<Lex> chosen = new ArrayList<>();
		List<Lex> alternatives = new ArrayList<>();
		Set<ChainId> staleChains = new java.util.LinkedHashSet<>();
		for (List<Lex> ranked : byChain.values()) {
			ranked.sort(Comparator.comparingDouble(Lex::score).reversed()
					.thenComparing(lex -> lex.row().getPriceAmount())
					.thenComparing(lex -> lex.row().getDisplayName().length()));
			Lex best = ranked.get(0);
			int firstAlternative = 0;
			if (best.score() >= config.getMinScore()) {
				chosen.add(best);
				firstAlternative = 1;
				if (best.row().getCapturedAt().isBefore(staleBefore)) {
					staleChains.add(ChainId.valueOf(best.row().getChainId().toUpperCase(Locale.ROOT)));
				}
			}
			ranked.stream().skip(firstAlternative)
					.filter(lex -> lex.score() >= config.getAlternativeMinScore())
					.limit(config.getMaxAlternativesPerChain())
					.forEach(alternatives::add);
		}
		Set<ChainId> missing = new java.util.LinkedHashSet<>(List.of(ChainId.values()));
		chosen.forEach(lex -> missing.remove(ChainId.valueOf(lex.row().getChainId().toUpperCase(Locale.ROOT))));
		return new Lexical(chosen, alternatives, missing, staleChains, !staleChains.isEmpty());
	}

	private static MatchedProduct toProduct(Lex lex) {
		NormalizedProduct row = lex.row();
		PackSize size = row.getPackUnit() == null || row.getPackQuantity() == null ? null
				: new PackSize(PackUnit.fromCode(row.getPackUnit()), row.getPackQuantity());
		return new MatchedProduct(row.getChainId().toUpperCase(Locale.ROOT), row.getStoreId(), row.getDisplayName(),
				row.getBrand(), row.getPriceAmount(), row.getCurrency(), row.isPromoFlag(), row.getCapturedAt(),
				Math.round(lex.score() * 100.0) / 100.0, row.getUnitPrice(), size == null ? null : size.label());
	}

	/** Words that mark a product as a variant of the plain one; a plain search should not pick them unasked. */
	private static final Set<String> VARIANT_WORDS = Set.of("lite", "light", "trim", "skim", "skimmed", "a2",
			"organic", "lactose", "lacto", "zero", "protein", "calci", "jersey", "homogenised", "flavoured", "reduced",
			"free", "gluten", "vegan", "premium", "diet", "sugar");

	private record Ranked(NormalizedProduct row, boolean sizeMatch, double coverage, int unaskedVariants) {
	}

	/**
	 * The fast path: the request names a common NZ product, so the candidates are exactly the stored
	 * products normalised to that product - no scan of the whole price history. A stored product must
	 * share at least one word with the request (broad keys such as spices hold many different
	 * products) and word coverage outranks pack size. Returns null when nothing qualifies, so the
	 * caller falls back to word-overlap matching.
	 */
	private MatchResult matchCanonical(String term, String itemId, CommonProduct common, PackSize requested,
			OffsetDateTime since, PricingCacheProperties.Match config, boolean stale) {
		List<NormalizedProduct> rows = dedupe(snapshot.findByCanonicalKeyAndCapturedAtAfter(common.key(), since));
		Set<String> termWords = ProductNormalizer.words(term);
		PackSize wanted = requested != null ? requested : common.typicalSize();
		Map<String, List<Ranked>> byChain = new LinkedHashMap<>();
		for (NormalizedProduct row : rows) {
			if (row.getPriceAmount() == null || row.getPriceAmount().signum() <= 0 || !knownChain(row.getChainId())) {
				continue;
			}
			Set<String> nameWords = ProductNormalizer.words(row.getDisplayName());
			long shared = termWords.stream().filter(nameWords::contains).count();
			double coverage = termWords.isEmpty() ? 0.0 : (double) shared / termWords.size();
			if (!termWords.isEmpty() && shared == 0) {
				continue;
			}
			boolean sizeMatch = wanted != null && row.getPackUnit() != null && row.getPackQuantity() != null
					&& wanted.sameAs(new PackSize(PackUnit.fromCode(row.getPackUnit()), row.getPackQuantity()));
			int unaskedVariants = (int) nameWords.stream()
					.filter(word -> VARIANT_WORDS.contains(word) && !termWords.contains(word)).count();
			byChain.computeIfAbsent(row.getChainId(), chain -> new ArrayList<>())
					.add(new Ranked(row, sizeMatch, coverage, unaskedVariants));
		}
		if (byChain.isEmpty()) {
			return null;
		}
		Comparator<Ranked> ranking = Comparator.comparingDouble(Ranked::coverage).reversed()
				.thenComparing(Comparator.comparing(Ranked::sizeMatch).reversed())
				.thenComparingInt(Ranked::unaskedVariants)
				.thenComparing(ranked -> ranked.row().getUnitPrice(), Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(ranked -> ranked.row().getPriceAmount());
		List<MatchedProduct> matches = new ArrayList<>();
		List<MatchedProduct> alternatives = new ArrayList<>();
		List<Candidate> chosen = new ArrayList<>();
		for (List<Ranked> ranked : byChain.values()) {
			ranked.sort(ranking);
			Ranked best = ranked.get(0);
			int firstAlternative = 0;
			if (requested == null || best.sizeMatch()) {
				matches.add(toProduct(best, config));
				chosen.add(new Candidate(best.row(), best.row().getDisplayName(),
						Math.max(best.coverage(), config.getMinScore())));
				firstAlternative = 1;
			}
			ranked.stream().skip(firstAlternative).limit(config.getMaxAlternativesPerChain())
					.forEach(other -> alternatives.add(toProduct(other, config)));
		}
		matches.sort(Comparator.comparing(MatchedProduct::priceAmount));
		alternatives.sort(Comparator.comparingDouble(MatchedProduct::score).reversed()
				.thenComparing(MatchedProduct::priceAmount));
		persistUnderItemId(itemId, chosen);
		return new MatchResult(METHOD_CANONICAL, matches, List.copyOf(alternatives), stale);
	}

	/**
	 * One row per distinct product per chain: a chain lists the same product under several categories
	 * (and stores), each with its own item id. Keeps the newest observation.
	 */
	private static List<NormalizedProduct> dedupe(List<NormalizedProduct> rows) {
		Map<String, NormalizedProduct> newest = new LinkedHashMap<>();
		for (NormalizedProduct row : rows) {
			String key = row.getChainId() + "|" + row.getNormalizedName() + "|" + row.getPackUnit() + "|"
					+ (row.getPackQuantity() == null ? "" : row.getPackQuantity().stripTrailingZeros().toPlainString());
			newest.merge(key, row, (existing, candidate) ->
					candidate.getCapturedAt().isAfter(existing.getCapturedAt()) ? candidate : existing);
		}
		return List.copyOf(newest.values());
	}

	private static MatchedProduct toProduct(Ranked ranked, PricingCacheProperties.Match config) {
		NormalizedProduct row = ranked.row();
		PackSize size = row.getPackUnit() == null || row.getPackQuantity() == null ? null
				: new PackSize(PackUnit.fromCode(row.getPackUnit()), row.getPackQuantity());
		double score = ranked.sizeMatch() ? Math.max(ranked.coverage(), config.getMinScore()) : ranked.coverage();
		return new MatchedProduct(row.getChainId().toUpperCase(Locale.ROOT), row.getStoreId(), row.getDisplayName(),
				row.getBrand(), row.getPriceAmount(), row.getCurrency(), row.isPromoFlag(), row.getCapturedAt(),
				Math.round(score * 100.0) / 100.0, row.getUnitPrice(), size == null ? null : size.label());
	}

	/**
	 * Stores the chosen products under the caller's item id (price_records, price_stats, PriceRecordCaptured) so that
	 * CompareShoppingList for that item prices exactly them. Runs on a background thread, after the answer is returned:
	 * it is several database round trips, and the caller must not wait for them.
	 */
	private void persistUnderItemId(String itemId, List<Candidate> chosen) {
		if (!StringUtils.hasText(itemId) || chosen.isEmpty()) {
			return;
		}
		List<Candidate> copy = List.copyOf(chosen);
		persistExecutor.execute(() -> {
			try {
				persistNow(itemId, copy);
			}
			catch (RuntimeException e) {
				log.warn("Could not store the matched prices under item {}: {}", itemId, e.getMessage());
			}
		});
	}

	private void persistNow(String itemId, List<Candidate> chosen) {
		Set<String> alreadyStored = new HashSet<>();
		repository.findByItemId(itemId).forEach(row -> alreadyStored.add(storedKey(row)));
		for (Candidate candidate : chosen) {
			NormalizedProduct source = candidate.row();
			if (!alreadyStored.add(storedKey(source.getChainId(), source.getStoreId(), source.getCapturedAt()))) {
				continue;
			}
			Map<String, Object> attributes = new LinkedHashMap<>();
			attributes.put("name", source.getDisplayName());
			if (StringUtils.hasText(source.getBrand())) {
				attributes.put("brand", source.getBrand());
			}
			PriceRecord saved = repository.save(PriceRecord.builder()
					.itemId(itemId)
					.storeId(source.getStoreId())
					.chainId(source.getChainId())
					.channel("pickup")
					.priceAmount(source.getPriceAmount())
					.currency(source.getCurrency())
					.promoFlag(source.isPromoFlag())
					.capturedAt(source.getCapturedAt())
					.rawAttributes(attributes)
					.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
					.build());
			eventPublisher.publishPriceRecordCaptured(saved);
			priceStatsService.recordObservation(saved);
		}
	}

	/** Test seam: the default stores on a daemon background thread. */
	void setPersistExecutor(Executor persistExecutor) {
		this.persistExecutor = persistExecutor;
	}

	/** One background ingest run at most every refresh-cooldown, and never two at once. */
	private void triggerIngest() {
		IngestScheduler scheduler = ingestScheduler.getIfAvailable();
		if (scheduler == null) {
			log.info("No stored match and ingest is disabled; nothing to refresh");
			return;
		}
		if (Instant.now().isBefore(lastIngestTrigger.plus(properties.getMatch().getRefreshCooldown()))
				|| !ingestRunning.compareAndSet(false, true)) {
			return;
		}
		lastIngestTrigger = Instant.now();
		log.info("No stored match: starting an on-demand ingest run");
		ingestExecutor.execute(() -> {
			try {
				scheduler.runAll();
			} catch (RuntimeException e) {
				log.error("On-demand ingest run failed", e);
			} finally {
				ingestRunning.set(false);
			}
		});
	}

	private static boolean knownChain(String chainId) {
		try {
			ChainId.valueOf(chainId.toUpperCase(Locale.ROOT));
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String storedKey(PriceRecord row) {
		return storedKey(row.getChainId(), row.getStoreId(), row.getCapturedAt());
	}

	private static String storedKey(String chainId, String storeId, OffsetDateTime capturedAt) {
		return chainId + "|" + storeId + "|" + (capturedAt == null ? "" : capturedAt.toInstant());
	}
}
