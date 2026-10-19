package com.stocker.pricing.refresh;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Makes sure the prices shown for a common product are recent: for each chain that sells it, if the
 * newest stored observation is older than {@code staleAfter}, load just that product's category page
 * (see {@link CategoryMap}). The supermarkets are protected by: a per-page cooldown, an hourly cap per
 * chain, one page load at a time per chain, and the ingest's own politeness delay. If the refresh does
 * not finish within the deadline the caller gets what is stored, flagged stale, and the refresh
 * completes in the background.
 */
@Service
public class OnDemandRefreshService {

	private static final Logger log = LoggerFactory.getLogger(OnDemandRefreshService.class);

	public record Outcome(boolean stale, boolean refreshed) {
	}

	private final NormalizedProductRepository normalizedRepository;
	private final CategoryMap categoryMap;
	private final CategoryRouter router;
	private final PricingCacheProperties properties;
	private final ObjectProvider<IngestScheduler> ingestScheduler;

	private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();
	private final Map<ChainId, ArrayDeque<Instant>> loadsPerChain = new EnumMap<>(ChainId.class);
	private final Map<ChainId, ReentrantLock> chainLocks = new EnumMap<>(ChainId.class);
	private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "on-demand-refresh");
		thread.setDaemon(true);
		return thread;
	});

	public OnDemandRefreshService(NormalizedProductRepository normalizedRepository, CategoryMap categoryMap,
			CategoryRouter router, PricingCacheProperties properties, ObjectProvider<IngestScheduler> ingestScheduler) {
		this.normalizedRepository = normalizedRepository;
		this.categoryMap = categoryMap;
		this.router = router;
		this.properties = properties;
		this.ingestScheduler = ingestScheduler;
		for (ChainId chain : ChainId.values()) {
			loadsPerChain.put(chain, new ArrayDeque<>());
			chainLocks.put(chain, new ReentrantLock());
		}
	}

	/**
	 * A common product: refresh the chains whose newest price for it is older than {@code staleAfter}.
	 *
	 * @param afterBackgroundRefresh run once, only if the refresh outlasted the deadline and finished later
	 */
	public Outcome ensureFresh(CommonProduct product, Runnable afterBackgroundRefresh) {
		PricingCacheProperties.OnDemand config = properties.getOnDemand();
		Map<ChainId, String> stale = staleTargets(product, config);
		if (stale.isEmpty()) {
			return new Outcome(false, false);
		}
		return refreshPages(stale, () -> outdated(product, config), product.key(), afterBackgroundRefresh);
	}

	/**
	 * Any other item: route the term to the category page it most likely lives on, on each of the given
	 * chains (those with no good stored match), and load just those pages.
	 */
	public Outcome ensureForTerm(String term, String categoryHint, Collection<ChainId> chains,
			Runnable afterBackgroundRefresh) {
		Map<ChainId, String> targets = new EnumMap<>(ChainId.class);
		for (ChainId chain : chains) {
			router.route(chain, term, categoryHint).ifPresent(url -> targets.put(chain, url));
		}
		if (targets.isEmpty()) {
			return new Outcome(false, false);
		}
		return refreshPages(targets, () -> false, "term '" + term + "'", afterBackgroundRefresh);
	}

	private Outcome refreshPages(Map<ChainId, String> pages, BooleanSupplier outdated, String label,
			Runnable afterBackgroundRefresh) {
		PricingCacheProperties.OnDemand config = properties.getOnDemand();
		IngestScheduler scheduler = ingestScheduler.getIfAvailable();
		if (!config.isEnabled() || scheduler == null) {
			return new Outcome(outdated.getAsBoolean(), false);
		}

		Map<ChainId, String> allowed = new EnumMap<>(ChainId.class);
		pages.forEach((chain, url) -> {
			if (permit(chain, url, config)) {
				allowed.put(chain, url);
			}
		});
		if (allowed.isEmpty()) {
			return new Outcome(outdated.getAsBoolean(), false);
		}

		List<CompletableFuture<Void>> jobs = new ArrayList<>();
		allowed.forEach((chain, url) -> jobs.add(CompletableFuture.runAsync(() -> load(scheduler, chain, url), executor)));
		CompletableFuture<Void> all = CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0]));
		try {
			all.get(config.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.info("On-demand refresh for {} still running after {}; answering with stored data", label,
					config.getDeadline());
			all.thenRun(afterBackgroundRefresh);
			return new Outcome(outdated.getAsBoolean(), false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Outcome(outdated.getAsBoolean(), false);
		} catch (Exception e) {
			log.warn("On-demand refresh for {} failed: {}", label, e.getMessage());
		}
		return new Outcome(outdated.getAsBoolean(), true);
	}

	/** True when some chain still shows this product with data older than the stale threshold. */
	private boolean outdated(CommonProduct product, PricingCacheProperties.OnDemand config) {
		OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minus(config.getStaleAfter());
		for (Object[] row : normalizedRepository.newestByChain(product.key())) {
			if (((OffsetDateTime) row[1]).isBefore(threshold)) {
				return true;
			}
		}
		return false;
	}

	private void load(IngestScheduler scheduler, ChainId chain, String url) {
		ReentrantLock lock = chainLocks.get(chain);
		lock.lock();
		try {
			int saved = scheduler.refreshCategories(chain, List.of(url));
			log.info("On-demand refresh {} {} -> {} records", chain, url, saved);
		} finally {
			lock.unlock();
		}
	}

	private Map<ChainId, String> staleTargets(CommonProduct product, PricingCacheProperties.OnDemand config) {
		Map<String, OffsetDateTime> newest = new HashMap<>();
		for (Object[] row : normalizedRepository.newestByChain(product.key())) {
			newest.put(String.valueOf(row[0]).toLowerCase(Locale.ROOT), (OffsetDateTime) row[1]);
		}
		OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minus(config.getStaleAfter());
		Map<ChainId, String> targets = new EnumMap<>(ChainId.class);
		for (ChainId chain : ChainId.values()) {
			categoryMap.url(product.key(), chain).ifPresent(url -> {
				OffsetDateTime at = newest.get(chain.name().toLowerCase(Locale.ROOT));
				if (at == null || at.isBefore(threshold)) {
					targets.put(chain, url);
				}
			});
		}
		return targets;
	}

	/** Cooldown per page and an hourly cap per chain; records the attempt when it grants permission. */
	private synchronized boolean permit(ChainId chain, String url, PricingCacheProperties.OnDemand config) {
		Instant now = Instant.now();
		Instant previous = lastAttempt.get(url);
		if (previous != null && now.isBefore(previous.plus(config.getCategoryCooldown()))) {
			return false;
		}
		ArrayDeque<Instant> loads = loadsPerChain.get(chain);
		while (!loads.isEmpty() && loads.peekFirst().isBefore(now.minusSeconds(3600))) {
			loads.pollFirst();
		}
		if (loads.size() >= config.getMaxPageLoadsPerChainPerHour()) {
			log.warn("On-demand page-load cap reached for {} ({} per hour); serving stored data", chain,
					config.getMaxPageLoadsPerChainPerHour());
			return false;
		}
		loads.addLast(now);
		lastAttempt.put(url, now);
		return true;
	}
}
