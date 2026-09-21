package com.stocker.pricing.service.match;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.model.PriceRecord;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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
	/** New World / PAK'nSave scrape the card's subtitle (the pack size, e.g. "2l") into the brand slot. */
	private static final Pattern SIZE_LIKE = Pattern.compile("^\\d+(?:\\.\\d+)?\\s*(?:l|ml|g|kg)$", Pattern.CASE_INSENSITIVE);

	public record MatchedProduct(String chainId, String storeId, String productName, String brand,
			BigDecimal priceAmount, String currency, boolean promoFlag, OffsetDateTime capturedAt, double score) {
	}

	public record MatchResult(String matchMethod, List<MatchedProduct> matches, List<MatchedProduct> alternatives) {
	}

	private record Candidate(PriceRecord record, String name, double score) {
	}

	private final PriceRecordRepository repository;
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
	private volatile Instant lastIngestTrigger = Instant.EPOCH;

	public ItemMatchService(PriceRecordRepository repository, PriceStatsService priceStatsService,
			EventPublisher eventPublisher, PricingCacheProperties properties,
			ObjectProvider<IngestScheduler> ingestScheduler) {
		this.repository = repository;
		this.priceStatsService = priceStatsService;
		this.eventPublisher = eventPublisher;
		this.properties = properties;
		this.ingestScheduler = ingestScheduler;
	}

	public MatchResult match(String term, String itemId) {
		PricingCacheProperties.Match config = properties.getMatch();
		OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minus(config.getMaxAge());
		Map<String, List<Candidate>> byChain = candidatesByChain(term, repository.findIngestedSince(since), config);

		List<Candidate> chosen = new ArrayList<>();
		List<MatchedProduct> alternatives = new ArrayList<>();
		for (List<Candidate> ranked : byChain.values()) {
			Candidate best = ranked.get(0);
			int firstAlternative = 0;
			if (best.score() >= config.getMinScore()) {
				chosen.add(best);
				firstAlternative = 1;
			}
			ranked.stream().skip(firstAlternative)
					.filter(candidate -> candidate.score() >= config.getAlternativeMinScore())
					.limit(config.getMaxAlternativesPerChain())
					.forEach(candidate -> alternatives.add(toProduct(candidate)));
		}

		List<MatchedProduct> matches = chosen.stream().map(ItemMatchService::toProduct)
				.sorted(Comparator.comparing(MatchedProduct::priceAmount)).toList();
		if (matches.isEmpty() && alternatives.isEmpty()) {
			triggerIngest();
		}
		persistUnderItemId(itemId, chosen);
		alternatives.sort(Comparator.comparingDouble(MatchedProduct::score).reversed()
				.thenComparing(MatchedProduct::priceAmount));
		return new MatchResult(METHOD_LEXICAL, matches, List.copyOf(alternatives));
	}

	/** Latest scraped price per product, scored against the request, best first, grouped by chain. */
	private Map<String, List<Candidate>> candidatesByChain(String term, List<PriceRecord> rows,
			PricingCacheProperties.Match config) {
		Map<String, PriceRecord> latestPerProduct = new LinkedHashMap<>();
		for (PriceRecord row : rows) {
			latestPerProduct.merge(row.getItemId(), row,
					(existing, candidate) -> candidate.getCapturedAt().isAfter(existing.getCapturedAt()) ? candidate : existing);
		}
		Map<String, List<Candidate>> byChain = new LinkedHashMap<>();
		for (PriceRecord row : latestPerProduct.values()) {
			String name = nameOf(row);
			if (!StringUtils.hasText(name) || !knownChain(row.getChainId())
					|| row.getPriceAmount() == null || row.getPriceAmount().signum() <= 0) {
				continue;
			}
			double score = NameMatcher.score(term, name);
			if (score >= config.getAlternativeMinScore()) {
				byChain.computeIfAbsent(row.getChainId(), chain -> new ArrayList<>()).add(new Candidate(row, name, score));
			}
		}
		byChain.values().forEach(list -> list.sort(Comparator.comparingDouble(Candidate::score).reversed()
				.thenComparing(candidate -> candidate.record().getPriceAmount())
				.thenComparing(candidate -> candidate.name().length())));
		return byChain;
	}

	private void persistUnderItemId(String itemId, List<Candidate> chosen) {
		if (!StringUtils.hasText(itemId) || chosen.isEmpty()) {
			return;
		}
		Set<String> alreadyStored = new HashSet<>();
		repository.findByItemId(itemId).forEach(row -> alreadyStored.add(storedKey(row)));
		for (Candidate candidate : chosen) {
			PriceRecord source = candidate.record();
			if (!alreadyStored.add(storedKey(source.getChainId(), source.getStoreId(), source.getCapturedAt()))) {
				continue;
			}
			PriceRecord saved = repository.save(PriceRecord.builder()
					.itemId(itemId)
					.storeId(source.getStoreId())
					.chainId(source.getChainId())
					.channel(source.getChannel())
					.priceAmount(source.getPriceAmount())
					.currency(source.getCurrency())
					.promoFlag(source.isPromoFlag())
					.capturedAt(source.getCapturedAt())
					.rawAttributes(source.getRawAttributes())
					.createdAt(OffsetDateTime.now(ZoneOffset.UTC))
					.build());
			eventPublisher.publishPriceRecordCaptured(saved);
			priceStatsService.recordObservation(saved);
		}
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

	private static MatchedProduct toProduct(Candidate candidate) {
		PriceRecord row = candidate.record();
		return new MatchedProduct(row.getChainId().toUpperCase(Locale.ROOT), row.getStoreId(), candidate.name(),
				realBrand(row), row.getPriceAmount(), row.getCurrency(), row.isPromoFlag(),
				row.getCapturedAt(), Math.round(candidate.score() * 100.0) / 100.0);
	}

	/** The product name as a shopper reads it: the title plus the pack size when the chain lists it separately. */
	private static String nameOf(PriceRecord row) {
		String name = attribute(row, "name");
		if (name == null) {
			return null;
		}
		String size = packSize(row);
		return size == null || name.toLowerCase(Locale.ROOT).contains(size.toLowerCase(Locale.ROOT))
				? name
				: name + " " + size;
	}

	private static String packSize(PriceRecord row) {
		String brand = attribute(row, "brand");
		return brand != null && SIZE_LIKE.matcher(brand.trim()).matches() ? brand.trim() : null;
	}

	private static String realBrand(PriceRecord row) {
		return packSize(row) == null ? attribute(row, "brand") : null;
	}

	private static String attribute(PriceRecord row, String key) {
		Object value = row.getRawAttributes() == null ? null : row.getRawAttributes().get(key);
		return value == null || value.toString().isBlank() ? null : value.toString();
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
