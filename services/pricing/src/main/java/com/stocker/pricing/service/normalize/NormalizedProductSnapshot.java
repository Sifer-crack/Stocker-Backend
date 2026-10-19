package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The normalised products, held in memory, so a price lookup costs no database round trip. Loaded with a single
 * query, reused for {@code app.pricing.snapshot.ttl} and reloaded in the background; a failed reload keeps serving
 * the last good copy. It offers the same read methods the lookups used on the repository, so callers only swap the
 * type. With a zero ttl every call passes straight through to the repository (the pre-snapshot behaviour).
 */
@Component
@EnableScheduling
public class NormalizedProductSnapshot {

	private static final Logger log = LoggerFactory.getLogger(NormalizedProductSnapshot.class);

	private record Loaded(List<NormalizedProduct> rows, Map<String, List<NormalizedProduct>> byKey, Instant loadedAt) {
	}

	private final NormalizedProductRepository repository;
	private final PricingCacheProperties properties;
	private final Clock clock;
	private volatile Loaded loaded;

	@Autowired
	public NormalizedProductSnapshot(NormalizedProductRepository repository, PricingCacheProperties properties) {
		this(repository, properties, Clock.systemUTC());
	}

	NormalizedProductSnapshot(NormalizedProductRepository repository, PricingCacheProperties properties, Clock clock) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public List<NormalizedProduct> findByCanonicalKeyAndCapturedAtAfter(String canonicalKey, OffsetDateTime since) {
		if (!enabled()) {
			return repository.findByCanonicalKeyAndCapturedAtAfter(canonicalKey, since);
		}
		return current().byKey().getOrDefault(canonicalKey, List.of()).stream()
				.filter(row -> !row.getCapturedAt().isBefore(since)).toList();
	}

	public List<NormalizedProduct> findByCapturedAtAfter(OffsetDateTime since) {
		if (!enabled()) {
			return repository.findByCapturedAtAfter(since);
		}
		return current().rows().stream().filter(row -> !row.getCapturedAt().isBefore(since)).toList();
	}

	/** Newest observation per chain for one common product: [chainId, capturedAt]. */
	public List<Object[]> newestByChain(String canonicalKey) {
		if (!enabled()) {
			return repository.newestByChain(canonicalKey);
		}
		Map<String, OffsetDateTime> newest = new HashMap<>();
		for (NormalizedProduct row : current().byKey().getOrDefault(canonicalKey, List.of())) {
			newest.merge(row.getChainId(), row.getCapturedAt(), (a, b) -> b.isAfter(a) ? b : a);
		}
		List<Object[]> result = new ArrayList<>();
		newest.forEach((chain, at) -> result.add(new Object[] { chain, at }));
		return result;
	}

	/** Reloads in the background so lookups never wait for the load; failures are logged, the last copy stays. */
	@Scheduled(fixedDelayString = "${app.pricing.snapshot.refresh-interval:PT15S}", initialDelay = 0)
	public void refresh() {
		if (!enabled()) {
			return;
		}
		try {
			load();
		}
		catch (RuntimeException e) {
			log.warn("Normalised-product snapshot refresh failed, keeping the previous copy: {}", e.getMessage());
		}
	}

	private boolean enabled() {
		Duration ttl = properties.getSnapshot().getTtl();
		return ttl != null && ttl.isPositive();
	}

	private Loaded current() {
		Loaded seen = loaded;
		if (seen != null && !expired(seen)) {
			return seen;
		}
		return reload(seen);
	}

	private boolean expired(Loaded copy) {
		return copy.loadedAt().plus(properties.getSnapshot().getTtl()).isBefore(clock.instant());
	}

	/** One thread reloads; the others wait for it (first load) or are served the stale copy (later loads). */
	private synchronized Loaded reload(Loaded seen) {
		if (loaded != null && loaded != seen) {
			return loaded;
		}
		try {
			return load();
		}
		catch (RuntimeException e) {
			if (loaded != null) {
				log.warn("Normalised-product reload failed, serving the previous copy: {}", e.getMessage());
				return loaded;
			}
			throw e;
		}
	}

	private Loaded load() {
		OffsetDateTime since = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))
				.minus(properties.getMatch().getCanonicalMaxAge());
		List<NormalizedProduct> rows = List.copyOf(repository.findByCapturedAtAfter(since));
		Map<String, List<NormalizedProduct>> byKey = new HashMap<>();
		for (NormalizedProduct row : rows) {
			if (row.getCanonicalKey() != null) {
				byKey.computeIfAbsent(row.getCanonicalKey(), key -> new ArrayList<>()).add(row);
			}
		}
		Loaded fresh = new Loaded(rows, byKey, clock.instant());
		loaded = fresh;
		return fresh;
	}
}
