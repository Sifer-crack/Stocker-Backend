package com.stocker.pricing.refresh;

import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
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

	private final NormalizedProductSnapshot normalizedRepository;
	private final CategoryMap categoryMap;
	private final CategoryRouter router;
	private final PricingCacheProperties properties;
	private final ObjectProvider<IngestScheduler> ingestScheduler;

	/** How long a finished state stays readable; after this the product has no progress until a new refresh starts. */
	private static final java.time.Duration PROGRESS_TTL = java.time.Duration.ofMinutes(30);

	private record Progress(RefreshState state, Instant at) {
	}

	private final Map<String, Map<ChainId, Progress>> progress = new ConcurrentHashMap<>();
	private final Map<String, Instant> lastAttempt = new ConcurrentHashMap<>();
	private final Map<ChainId, ArrayDeque<Instant>> loadsPerChain = new EnumMap<>(ChainId.class);
	private final Map<ChainId, ReentrantLock> chainLocks = new EnumMap<>(ChainId.class);
	private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "on-demand-refresh");
		thread.setDaemon(true);
		return thread;
	});

	public OnDemandRefreshService(NormalizedProductSnapshot normalizedRepository, CategoryMap categoryMap,
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
		return refreshPages(stale, () -> outdated(product, config), product.key(), commonLabel(product),
				afterBackgroundRefresh);
	}

	/** Per-chain state of the refresh started for this common product; empty when none is known. Reads memory only. */
	public Map<ChainId, RefreshState> progressForCommon(CommonProduct product) {
		return progressOf(commonLabel(product));
	}

	/** Per-chain state of the refresh started for this term; empty when none is known. Reads memory only. */
	public Map<ChainId, RefreshState> progressForTerm(String term) {
		return progressOf(termLabel(term));
	}

	private static String commonLabel(CommonProduct product) {
		return "common:" + product.key();
	}

	private static String termLabel(String term) {
		return "term:" + term.trim().toLowerCase(Locale.ROOT);
	}

	private Map<ChainId, RefreshState> progressOf(String label) {
		Map<ChainId, Progress> known = progress.get(label);
		Map<ChainId, RefreshState> result = new EnumMap<>(ChainId.class);
		if (known == null) {
			return result;
		}
		Instant oldest = Instant.now().minus(PROGRESS_TTL);
		known.forEach((chain, entry) -> {
			if (entry.state() == RefreshState.PENDING || !entry.at().isBefore(oldest)) {
				result.put(chain, entry.state());
			}
		});
		return result;
	}

	private void mark(String label, ChainId chain, RefreshState state, boolean onlyIfUnknown) {
		Map<ChainId, Progress> known = progress.computeIfAbsent(label, key -> new ConcurrentHashMap<>());
		Instant oldest = Instant.now().minus(PROGRESS_TTL);
		if (onlyIfUnknown) {
			known.compute(chain, (key, existing) -> existing != null
					&& (existing.state() == RefreshState.PENDING || !existing.at().isBefore(oldest)) ? existing
							: new Progress(state, Instant.now()));
		} else {
			known.put(chain, new Progress(state, Instant.now()));
		}
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
		return refreshPages(targets, () -> false, "term '" + term + "'", termLabel(term), afterBackgroundRefresh);
	}

	private Outcome refreshPages(Map<ChainId, String> pages, BooleanSupplier outdated, String label, String progressLabel,
			Runnable afterBackgroundRefresh) {
		PricingCacheProperties.OnDemand config = properties.getOnDemand();
		IngestScheduler scheduler = ingestScheduler.getIfAvailable();
		if (!config.isEnabled() || scheduler == null) {
			pages.keySet().forEach(chain -> mark(progressLabel, chain, RefreshState.SKIPPED, true));
			return new Outcome(outdated.getAsBoolean(), false);
		}

		// The request this refresh belongs to (the gRPC call running on this thread); background jobs run on other
		// threads, so its Context is captured here. A call that merely completes does not cancel the token.
		CancellationToken cancellation = CancellationToken.current();
		if (cancellation.isCancelled()) {
			pages.keySet().forEach(chain -> mark(progressLabel, chain, RefreshState.SKIPPED, true));
			return new Outcome(outdated.getAsBoolean(), false);
		}

		Map<ChainId, Permit> allowed = new EnumMap<>(ChainId.class);
		pages.forEach((chain, url) -> {
			Permit permit = permit(chain, url, config);
			if (permit != null) {
				allowed.put(chain, permit);
				mark(progressLabel, chain, RefreshState.PENDING, false);
			} else {
				mark(progressLabel, chain, RefreshState.SKIPPED, true);
			}
		});
		if (allowed.isEmpty()) {
			return new Outcome(outdated.getAsBoolean(), false);
		}

		List<CompletableFuture<Void>> jobs = new ArrayList<>();
		allowed.forEach((chain, permit) -> jobs.add(
				CompletableFuture.runAsync(() -> load(scheduler, chain, permit, cancellation, progressLabel), executor)));
		CompletableFuture<Void> all = CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0]));
		Runnable followUp = () -> {
			if (!cancellation.isCancelled()) {
				afterBackgroundRefresh.run();       // nobody is waiting for the fresh prices any more otherwise
			}
		};
		if (config.getDeadline().isZero() || config.getDeadline().isNegative()) {
			// Never make a lookup wait for a supermarket page: answer with what is stored (flagged stale) and let the
			// follow-up re-run the match, store the fresh prices and announce them once the pages are loaded.
			all.thenRun(followUp);
			return new Outcome(outdated.getAsBoolean(), false);
		}
		try {
			all.get(config.getDeadline().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			log.info("On-demand refresh for {} still running after {}; answering with stored data", label,
					config.getDeadline());
			all.thenRun(followUp);
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

	private void load(IngestScheduler scheduler, ChainId chain, Permit permit, CancellationToken cancellation,
			String progressLabel) {
		ReentrantLock lock = chainLocks.get(chain);
		if (!cancellation.lockUnlessCancelled(lock)) {
			refund(permit);
			mark(progressLabel, chain, RefreshState.SKIPPED, false);
			log.info("On-demand refresh {} {} cancelled before it started", chain, permit.url());
			return;
		}
		try {
			int saved = scheduler.refreshCategories(chain, List.of(permit.url()), cancellation);
			if (cancellation.isCancelled() && saved == 0) {
				refund(permit);       // cancelled and nothing came of it: do not charge the cooldown / hourly cap
				mark(progressLabel, chain, RefreshState.SKIPPED, false);
			} else {
				publishToSnapshot(saved);
				mark(progressLabel, chain, RefreshState.DONE, false);
			}
			log.info("On-demand refresh {} {} -> {} records", chain, permit.url(), saved);
		} catch (RuntimeException e) {
			mark(progressLabel, chain, RefreshState.FAILED, false);
			throw e;
		} finally {
			lock.unlock();
		}
	}

	/** The page is stored; make the in-memory copy show it now, so "done" means a re-asked search sees the prices. */
	private void publishToSnapshot(int saved) {
		if (saved > 0) {
			try {
				normalizedRepository.refresh();
			} catch (RuntimeException e) {
				log.warn("Snapshot refresh after an on-demand load failed: {}", e.getMessage());
			}
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

	/** One granted page load: what was recorded for it, so a load that never happens can be taken back. */
	private record Permit(ChainId chain, String url, Instant at, Instant previousAttempt) {
	}

	/** Cooldown per page and an hourly cap per chain; records the attempt and returns a permit when it grants one. */
	private synchronized Permit permit(ChainId chain, String url, PricingCacheProperties.OnDemand config) {
		Instant now = Instant.now();
		Instant previous = lastAttempt.get(url);
		if (previous != null && now.isBefore(previous.plus(config.getCategoryCooldown()))) {
			return null;
		}
		ArrayDeque<Instant> loads = loadsPerChain.get(chain);
		while (!loads.isEmpty() && loads.peekFirst().isBefore(now.minusSeconds(3600))) {
			loads.pollFirst();
		}
		if (loads.size() >= config.getMaxPageLoadsPerChainPerHour()) {
			log.warn("On-demand page-load cap reached for {} ({} per hour); serving stored data", chain,
					config.getMaxPageLoadsPerChainPerHour());
			return null;
		}
		loads.addLast(now);
		lastAttempt.put(url, now);
		return new Permit(chain, url, now, previous);
	}

	/** Takes back the cooldown stamp and the hourly-cap slot of a page that was never loaded. */
	private synchronized void refund(Permit permit) {
		loadsPerChain.get(permit.chain()).removeFirstOccurrence(permit.at());
		if (permit.url() != null && permit.at().equals(lastAttempt.get(permit.url()))) {
			if (permit.previousAttempt() == null) {
				lastAttempt.remove(permit.url());
			} else {
				lastAttempt.put(permit.url(), permit.previousAttempt());
			}
		}
	}
}
