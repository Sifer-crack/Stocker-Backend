package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.config.IngestProperties.ChainConfig;
import com.stocker.pricing.ingest.model.CategoryResultSink;
import com.stocker.pricing.ingest.model.CrawlBrake;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.RawProductPriceRecordMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import com.stocker.pricing.ingest.sitemap.StapleCategoryMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs each chain's {@link ChainScraper} on a cron and persists results into price_records.
 * Fully decoupled from the gRPC Search RPC / fetch/ module, which keep using third-party
 * providers for on-demand live lookups.
 */
public class IngestScheduler {

	private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

	/**
	 * Playwright objects are not thread-safe: two scrapes using the shared Browser at once fail with errors like
	 * "Cannot find object to call __adopt__". The nightly runs, the startup run and on-demand refreshes all
	 * take this lock, so exactly one scrape uses the browser at any time.
	 */
	private final Map<Object, ReentrantLock> browserLocks = new ConcurrentHashMap<>();

	private final List<ChainScraper> chainScrapers;
	private final ChainBrowsers browsers;
	private final IngestProperties properties;
	private final PriceRecordRepository priceRecordRepository;
	private final PriceStatsService priceStatsService;
	private final DailyPageBudget pageBudget;
	private final CrawlBreaker breaker;
	private final CategoryMap categoryMap;
	private final Clock clock;
	private final CategoryHealth categoryHealth;
	private final Function<ChainId, List<String>> discoveredStaplePages;
	private final Set<String> stapleKeywords = StapleCategoryMatcher.loadKeywords();
	private final Map<String, Instant> lastCrawled = new ConcurrentHashMap<>();
	/** Set by the Spring config: category pages must be https on the chain's own domain (see {@link ChainDomains}). */
	private volatile boolean ownDomainsOnly;
	/** On-demand refreshes only: page cap per category (0 = the scheduled crawl's cap) and the share of the day kept for the crawl. */
	private volatile int onDemandMaxPagesPerCategory;
	private volatile double onDemandKeepBudgetShare;

	public IngestScheduler(List<ChainScraper> chainScrapers, Browser browser, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService) {
		this(chainScrapers, browser, properties, priceRecordRepository, priceStatsService, CategoryMap.empty(),
				Clock.systemUTC());
	}

	public IngestScheduler(List<ChainScraper> chainScrapers, Browser browser, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService, CategoryMap categoryMap,
			Clock clock) {
		this(chainScrapers, browser, properties, priceRecordRepository, priceStatsService, categoryMap, clock,
				new CategoryHealth(properties.getQuarantineAfterZeroRuns(),
						Duration.ofDays(properties.getQuarantineDays()), properties.getQuarantineSiteWideZeroShare(), clock));
	}

	public IngestScheduler(List<ChainScraper> chainScrapers, Browser browser, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService, CategoryMap categoryMap,
			Clock clock, CategoryHealth categoryHealth) {
		this(chainScrapers, browser, properties, priceRecordRepository, priceStatsService, categoryMap, clock,
				categoryHealth, chain -> List.of());
	}

	/** @param discoveredStaplePages sitemap category pages that match a staple keyword, per chain (offline lookup) */
	public IngestScheduler(List<ChainScraper> chainScrapers, Browser browser, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService, CategoryMap categoryMap,
			Clock clock, CategoryHealth categoryHealth, Function<ChainId, List<String>> discoveredStaplePages) {
		this(chainScrapers, ChainBrowsers.shared(browser), properties, priceRecordRepository, priceStatsService,
				categoryMap, clock, categoryHealth, discoveredStaplePages);
	}

	/** Test seam without the staple discovery. */
	public IngestScheduler(List<ChainScraper> chainScrapers, ChainBrowsers browsers, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService, CategoryMap categoryMap,
			Clock clock) {
		this(chainScrapers, browsers, properties, priceRecordRepository, priceStatsService, categoryMap, clock,
				new CategoryHealth(properties.getQuarantineAfterZeroRuns(),
						Duration.ofDays(properties.getQuarantineDays()), properties.getQuarantineSiteWideZeroShare(), clock),
				chain -> List.of());
	}

	/**
	 * @param browsers which Browser each chain uses; with app.ingest.parallel-chains the scheduled run starts one
	 *        thread per chain and every chain has its own browser and lock
	 * @param discoveredStaplePages sitemap category pages that match a staple keyword, per chain (offline lookup)
	 */
	public IngestScheduler(List<ChainScraper> chainScrapers, ChainBrowsers browsers, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService, CategoryMap categoryMap,
			Clock clock, CategoryHealth categoryHealth, Function<ChainId, List<String>> discoveredStaplePages) {
		this.discoveredStaplePages = discoveredStaplePages;
		this.categoryHealth = categoryHealth;
		this.chainScrapers = chainScrapers;
		this.browsers = browsers;
		this.properties = properties;
		this.priceRecordRepository = priceRecordRepository;
		this.priceStatsService = priceStatsService;
		this.categoryMap = categoryMap;
		this.clock = clock;
		this.breaker = new CrawlBreaker(properties::getBreakerBlockedThreshold, properties::getBreakerCooldown,
				properties::getBreakerMaxCooldown, clock,
				properties::getBreakerSuccessesToReset);
		this.pageBudget = new DailyPageBudget(properties::getMaxPagesPerChainPerDay, clock, breaker);
	}

	/**
	 * From now on a configured, catalogued or sitemap-discovered category URL is only crawled when it is https on the
	 * chain's own domain; any other is logged at WARN and skipped. Returns this scheduler.
	 */
	public IngestScheduler restrictToChainDomains() {
		this.ownDomainsOnly = true;
		return this;
	}

	/**
	 * Limits for on-demand refreshes ({@link #refreshCategories}), so lookups cannot eat the scheduled crawl's day.
	 *
	 * @param maxPagesPerCategory most pages loaded for one category page (1 = no "next page"); 0 = no limit of its own
	 * @param keepBudgetShare share (0..1) of the chain's daily page budget an on-demand refresh may never touch
	 */
	public IngestScheduler limitOnDemand(int maxPagesPerCategory, double keepBudgetShare) {
		this.onDemandMaxPagesPerCategory = Math.max(0, maxPagesPerCategory);
		this.onDemandKeepBudgetShare = Math.min(1.0, Math.max(0.0, keepBudgetShare));
		return this;
	}

	/** True while the chain's circuit breaker is open: neither the schedule nor an on-demand refresh may load its pages. */
	public boolean isChainBlocked(ChainId chain) {
		return pageBudget.isBlocked(chain);
	}

	@Scheduled(cron = "${app.ingest.cron}", zone = "${app.ingest.cron-zone:Pacific/Auckland}")
	public void runAll() {
		if (properties.isParallelChains()) {
			runAllParallel();
		} else {
			for (ChainScraper scraper : chainScrapers) {
				try {
					runOne(scraper);
				} catch (Throwable t) {
					log.error("{}: crawl failed, the other chains carry on", scraper.chainId(), t);
				}
			}
		}
		List<CategoryHealth.QuarantinedPage> quarantined = categoryHealth.snapshot();
		if (!quarantined.isEmpty()) {
			log.warn("Quarantined category pages after this run ({}; in memory, a restart resets them): {}",
					quarantined.size(), quarantined);
		}
	}

	/** One thread per chain; a failure in one chain is logged and never stops the others. Waits for all of them. */
	private void runAllParallel() {
		log.info("Crawling {} chain(s) in parallel, each with its own browser", chainScrapers.size());
		List<Thread> threads = new ArrayList<>();
		for (ChainScraper scraper : chainScrapers) {
			Thread thread = new Thread(() -> {
				try {
					runOne(scraper);
				} catch (Throwable t) {
					log.error("{}: crawl failed, the other chains carry on", scraper.chainId(), t);
				}
			}, "ingest-" + scraper.chainId().name().toLowerCase());
			thread.setDaemon(true);
			threads.add(thread);
			thread.start();
		}
		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Interrupted while waiting for the parallel crawl; the chain threads finish on their own");
				return;
			}
		}
	}

	private ReentrantLock lockFor(ChainId chain) {
		return browserLocks.computeIfAbsent(browsers.lockKey(chain), key -> new ReentrantLock(true));
	}

	private void runOne(ChainScraper scraper) {
		ChainId chainId = scraper.chainId();
		ChainConfig chainConfig = resolveChainConfig(chainId);

		if (!chainConfig.isEnabled()) {
			log.info("{}: ingest disabled, skipping", chainId);
			return;
		}
		if (pageBudget.isBlocked(chainId)) {
			log.warn("{}: crawl circuit breaker is open until {}, skipping this run", chainId, breaker.openUntil(chainId));
			return;
		}
		CrawlLists lists = crawlLists(chainId, chainConfig);
		List<String> fullList = lists.all();
		// Quarantined pages are dropped BEFORE the budget window is chosen, so they take no slot in it.
		List<String> priority = withoutQuarantined(chainId, lists.priority());
		List<String> others = withoutQuarantined(chainId, lists.others());
		int crawlable = priority.size() + others.size();
		if (crawlable == 0 && !fullList.isEmpty()) {
			log.info("{}: all {} category page(s) are quarantined, nothing to crawl", chainId, fullList.size());
			return;
		}
		if (crawlable == 0) {
			log.warn("{}: no category pages to crawl (see app.ingest.{}.category-urls and category-map.txt)",
					chainId, chainId.name().toLowerCase());
			return;
		}
		// Common products and staples first, then a window of the rest that moves on each day. The first page of every
		// planned category is reserved up front; the budget left over (extra-pages-reserve-per-run, at most a fifth of what is left) pays for the further pages of multi-page categories.
		int remaining = pageBudget.remaining(chainId);
		int window = properties.getMaxCategoriesPerRun() > 0
				? properties.getMaxCategoriesPerRun()
				: Math.min(crawlable, Math.max(1, remaining - Math.min(properties.getExtraPagesReservePerRun(), remaining / 5)));
		List<String> planned = CrawlPlanner.plan(priority, others, lastCrawled,
				Duration.ofHours(properties.getRecrawlFreshnessHours()), clock.instant(), window, dayNumber());
		log.info("{}: {} priority + {} other page(s) known, {} planned today (budget left {})", chainId, priority.size(),
				others.size(), planned.size(), remaining);
		scrapeAndPersist(scraper, chainConfig, planned, CancellationToken.NONE, false);
	}

	private record CrawlLists(List<String> priority, List<String> others) {
		List<String> all() {
			List<String> all = new ArrayList<>(priority);
			all.addAll(others);
			return all;
		}
	}

	/**
	 * Priority = the catalogue's category pages, then configured pages and sitemap-discovered pages that match a
	 * staple keyword; others = the remaining configured pages. Each page once.
	 */
	private CrawlLists crawlLists(ChainId chainId, ChainConfig chainConfig) {
		Map<String, String> priority = new LinkedHashMap<>();
		Map<String, String> others = new LinkedHashMap<>();
		for (String url : ownOnly(chainId, categoryMap.urls(chainId), "category-map")) {
			priority.putIfAbsent(pageKey(url), url);
		}
		for (String url : ownOnly(chainId, chainConfig.getCategoryUrls(), "configured")) {
			if (priority.containsKey(pageKey(url))) {
				continue;
			}
			if (StapleCategoryMatcher.matches(url, stapleKeywords)) {
				priority.putIfAbsent(pageKey(url), url);
			} else {
				others.putIfAbsent(pageKey(url), url);
			}
		}
		if (properties.isStapleDiscoveryEnabled()) {
			for (String url : ownOnly(chainId, discoverStaplePages(chainId), "sitemap-discovered")) {
				String key = pageKey(url);
				if (!priority.containsKey(key) && !others.containsKey(key)) {
					priority.put(key, url);
				}
			}
		}
		return new CrawlLists(List.copyOf(priority.values()), List.copyOf(others.values()));
	}

	private List<String> ownOnly(ChainId chainId, List<String> urls, String source) {
		return ownDomainsOnly ? ChainDomains.onlyOwn(chainId, urls, source) : urls;
	}

	private List<String> discoverStaplePages(ChainId chainId) {
		try {
			return discoveredStaplePages.apply(chainId);
		} catch (RuntimeException e) {
			log.warn("{}: sitemap staple discovery failed, crawling the known pages only: {}", chainId, e.getMessage());
			return List.of();
		}
	}

	private List<String> withoutQuarantined(ChainId chainId, List<String> urls) {
		return urls.stream().filter(url -> !categoryHealth.isQuarantined(chainId, url)).toList();
	}

	private static String pageKey(String url) {
		return url.replaceAll("\\?pg=1$", "");
	}

	/**
	 * Loads exactly these category pages of one chain and stores what is on them. Used by the
	 * on-demand freshness refresh; the caller owns rate limiting. Returns the number of records stored.
	 */
	public int refreshCategories(ChainId chainId, List<String> categoryUrls) {
		return refreshCategories(chainId, categoryUrls, CancellationToken.NONE);
	}

	/**
	 * As {@link #refreshCategories(ChainId, List)}, for a refresh that belongs to a request: when {@code cancellation}
	 * is cancelled no further page is loaded (the page being loaded finishes) and the daily budget reserved for the
	 * pages never visited is given back. The scheduled crawl never passes a token.
	 */
	public int refreshCategories(ChainId chainId, List<String> categoryUrls, CancellationToken cancellation) {
		if (cancellation.isCancelled()) {
			return 0;
		}
		ChainConfig chainConfig = resolveChainConfig(chainId);
		if (!chainConfig.isEnabled() || categoryUrls.isEmpty()) {
			return 0;
		}
		List<String> pages = withoutQuarantined(chainId, ownOnly(chainId, categoryUrls, "on-demand"));
		if (pages.isEmpty()) {
			return 0;
		}
		return chainScrapers.stream().filter(scraper -> scraper.chainId() == chainId).findFirst()
				.map(scraper -> scrapeAndPersist(scraper, chainConfig, pages, cancellation, true)).orElse(0);
	}

	private int scrapeAndPersist(ChainScraper scraper, ChainConfig chainConfig, List<String> categoryUrls,
			CancellationToken cancellation, boolean onDemand) {
		ChainId chainId = scraper.chainId();
		if (pageBudget.isBlocked(chainId)) {
			log.warn("{}: crawl circuit breaker is open until {}, not loading {} page(s)", chainId,
					breaker.openUntil(chainId), categoryUrls.size());
			return 0;
		}
		// An on-demand refresh may never take the last share of the day: it belongs to the scheduled crawl.
		int keepFree = onDemand ? (int) Math.ceil(pageBudget.cap() * onDemandKeepBudgetShare) : 0;
		int granted = pageBudget.reserve(chainId, categoryUrls.size(), keepFree);
		if (granted == 0) {
			log.warn("{}: daily page budget ({} per day{}) used up, not loading {} page(s)", chainId,
					properties.getMaxPagesPerChainPerDay(), keepFree > 0 ? ", " + keepFree + " kept for the scheduled crawl" : "",
					categoryUrls.size());
			return 0;
		}
		if (granted < categoryUrls.size()) {
			log.warn("{}: daily page budget leaves {} of {} requested page(s)", chainId, granted, categoryUrls.size());
			categoryUrls = categoryUrls.subList(0, granted);
		}
		AtomicInteger extraPages = new AtomicInteger();
		IncrementalSave save = new IncrementalSave(chainId);
		CountingBrake brake = new CountingBrake(breaker.forChain(chainId));
		int pageCap = properties.maxPagesPerCategory(chainId);
		if (onDemand && onDemandMaxPagesPerCategory > 0) {
			pageCap = Math.min(pageCap, onDemandMaxPagesPerCategory);
		}
		ChainScraperConfig config = ChainScraperConfig.builder()
				.storeId(chainConfig.getStoreId())
				.storeName(chainConfig.getStoreName())
				.categoryUrls(categoryUrls)
				.maxPagesPerCategory(pageCap)
				.navigationTimeoutSeconds(properties.getNavigationTimeoutSeconds())
				.pageDelayMinMillis(properties.pageDelayMinMillis(chainId))
				.pageDelayMaxMillis(properties.pageDelayMaxMillis(chainId))
				.crawlBrake(brake)
				.cancellation(cancellation)
				.categoryResultSink(save)
				.extraPageGate(() -> {
					boolean allowed = pageBudget.reserve(chainId, 1, keepFree) == 1;
					if (allowed) {
						extraPages.incrementAndGet();
					}
					return allowed;
				})
				.build();

		ScrapeResult result = null;
		ReentrantLock browserLock = lockFor(chainId);
		if (!cancellation.lockUnlessCancelled(browserLock)) {
			pageBudget.giveBack(chainId, granted);       // cancelled while waiting for the browser: nothing was loaded
			log.info("{}: refresh cancelled before it started, {} page(s) returned to the budget", chainId, granted);
			return 0;
		}
		try {
			result = scraper.scrape(browsers.forChain(chainId), config);
		} catch (ScrapeException e) {
			log.error("{}: scrape failed, skipping this run ({} row(s) of finished categories were already stored)",
					chainId, save.saved);
			return save.saved;
		} finally {
			browserLock.unlock();
			if (result == null) {
				// The scrape threw (browser died, context failed ...): the pages it never requested go back to the budget
				// instead of burning the whole day for nothing. Requests that were sent stay counted.
				int firstPagesRequested = Math.max(brake.requested() - extraPages.get(), save.categoryCalls);
				pageBudget.giveBack(chainId, granted - firstPagesRequested);
			}
		}

		// Reserved but never requested goes back. A first page that was requested and failed (timeout, error status,
		// block) is NOT in categoriesVisited but did cost the supermarket a request: it stays counted.
		int firstPagesRequested = Math.max(brake.requested() - extraPages.get(), result.getCategoriesVisited().size());
		pageBudget.giveBack(chainId, granted - firstPagesRequested);
		// Categories whose rows could not be stored are neither "crawled" (they come round again soon) nor judged by
		// CategoryHealth (their products were found, but nothing was kept).
		Set<String> notStored = save.failedCategories;
		categoryHealth.record(chainId, withoutVisited(result, notStored));
		Instant crawledAt = clock.instant();
		result.getCategoriesVisited().stream().filter(url -> !notStored.contains(url))
				.forEach(url -> lastCrawled.put(url, crawledAt));
		if (!notStored.isEmpty()) {
			log.warn("{}: rows of {} categor{} could not be stored; they are retried on the next run: {}", chainId,
					notStored.size(), notStored.size() == 1 ? "y" : "ies", notStored);
		}
		if (extraPages.get() > 0) {
			log.info("{}: {} further page(s) of multi-page categories loaded, budget left today: {}", chainId,
					extraPages.get(), pageBudget.remaining(chainId));
		}

		if (!result.getZeroResultCategoryUrls().isEmpty()) {
			log.warn("{}: {} of {} category page(s) returned zero products: {}", chainId,
					result.getZeroResultCategoryUrls().size(), result.getCategoriesVisited().size(),
					result.getZeroResultCategoryUrls());
		}
		if (!result.getWarnings().isEmpty()) {
			log.warn("{}: {} warning(s) during scrape: {}", chainId, result.getWarnings().size(), result.getWarnings());
		}

		log.info("{}: scraped {} products from {} categor{}", chainId, result.getProducts().size(),
				result.getCategoriesVisited().size(), result.getCategoriesVisited().size() == 1 ? "y" : "ies");

		if (properties.isDryRun()) {
			log.info("{}: dry-run enabled, not persisting", chainId);
			return 0;
		}

		if (save.categoryCalls == 0) {
			// The scraper never reported a category (older scraper): store the whole run now, as before.
			if (!save.persist("end of run", result.getProducts())) {
				result.getCategoriesVisited().forEach(lastCrawled::remove);
			}
		}
		log.info("{}: persisted {} price records ({} after de-dupe, {} categor{} stored, {} failed)", chainId, save.saved,
				save.seen.size(), save.categoryCalls, save.categoryCalls == 1 ? "y" : "ies", save.failures);
		return save.saved;
	}

	/** The result as CategoryHealth should judge it: categories whose rows were not stored are left out. */
	private static ScrapeResult withoutVisited(ScrapeResult result, Set<String> skip) {
		if (skip.isEmpty()) {
			return result;
		}
		return ScrapeResult.builder().chainId(result.getChainId()).products(result.getProducts())
				.categoriesVisited(result.getCategoriesVisited().stream().filter(url -> !skip.contains(url)).toList())
				.zeroResultCategoryUrls(result.getZeroResultCategoryUrls()).warnings(result.getWarnings()).build();
	}

	/** The chain's breaker, plus a count of the page requests the scraper really sent. */
	private static final class CountingBrake implements CrawlBrake {

		private final CrawlBrake delegate;
		private final AtomicInteger requested = new AtomicInteger();

		CountingBrake(CrawlBrake delegate) {
			this.delegate = delegate;
		}

		int requested() {
			return requested.get();
		}

		@Override
		public void onPageRequested() {
			requested.incrementAndGet();
			delegate.onPageRequested();
		}

		@Override
		public boolean isOpen() {
			return delegate.isOpen();
		}

		@Override
		public void onSuccess() {
			delegate.onSuccess();
		}

		@Override
		public void onBlocked(String reason, java.time.Duration retryAfter) {
			delegate.onBlocked(reason, retryAfter);
		}
	}

	/**
	 * The scheduler's {@link CategoryResultSink}: stores each category's rows as soon as the scraper reports it, so a
	 * crash, restart or breaker trip later in the run loses nothing already scraped. Same path as ever (price_records
	 * append plus {@link PriceStatsService#recordObservation}), de-duplicated by native product code across the whole run.
	 * One run, one scraper thread: not shared between threads.
	 */
	private final class IncrementalSave implements CategoryResultSink {

		private final ChainId chainId;
		private final Set<String> seen = new HashSet<>();
		private int saved;
		private int failures;
		private int categoryCalls;
		/** Categories with at least one row that could not be stored. */
		private final Set<String> failedCategories = new HashSet<>();

		IncrementalSave(ChainId chainId) {
			this.chainId = chainId;
		}

		@Override
		public void onCategory(String categoryUrl, List<RawProduct> products) {
			categoryCalls++;
			if (properties.isDryRun()) {
				return;
			}
			int before = saved;
			try {
				if (persist(categoryUrl, products)) {
					log.info("{}: stored {} rows from {} (run total {})", chainId, saved - before, categoryUrl, saved);
				} else {
					failedCategories.add(categoryUrl);
				}
			} catch (RuntimeException e) {
				failedCategories.add(categoryUrl);
				failures++;
				log.error("{}: storing the rows of {} failed, the crawl carries on: {}", chainId, categoryUrl, e.toString());
			}
		}

		/**
		 * Stores the products not yet stored in this run; a failure is logged and counted, never thrown. The rows go in
		 * chunks of {@code persistBatchSize}, each chunk through {@link PriceStatsService#saveAndRecordAll} (one
		 * transaction, a fixed number of round trips). A chunk that fails is rolled back as a whole and then retried row
		 * by row on the old path, so one bad row costs only itself.
		 */
		boolean persist(String source, List<RawProduct> products) {
			boolean ok = true;
			List<PriceRecord> chunk = new ArrayList<>();
			List<String> chunkCodes = new ArrayList<>();
			int chunkSize = Math.max(1, properties.getPersistBatchSize());
			for (RawProduct product : products) {
				if (!seen.add(product.getNativeProductCode())) {
					continue;       // same product in two categories: stored once
				}
				try {
					chunk.add(RawProductPriceRecordMapper.toPriceRecord(product));
					chunkCodes.add(product.getNativeProductCode());
				} catch (IllegalArgumentException e) {
					log.warn("{}: skipping product, could not derive itemId: {}", chainId, e.getMessage());
					continue;
				}
				if (chunk.size() >= chunkSize) {
					ok &= storeChunk(source, chunk, chunkCodes);
				}
			}
			if (!chunk.isEmpty()) {
				ok &= storeChunk(source, chunk, chunkCodes);
			}
			return ok;
		}

		private boolean storeChunk(String source, List<PriceRecord> chunk, List<String> codes) {
			List<PriceRecord> records = List.copyOf(chunk);
			List<String> chunkCodes = List.copyOf(codes);
			chunk.clear();
			codes.clear();
			List<Long> backoff = properties.getPersistRetryBackoffMillis();
			int retry = 0;
			while (true) {
				try {
					priceStatsService.saveAndRecordAll(records);
					saved += records.size();
					return true;
				} catch (RuntimeException batchFailure) {
					// A dropped connection (Neon compute restart) rolled the whole chunk back: the same rows are safe to
					// send again once the pool has a fresh connection. Anything else goes straight to the row-by-row path.
					if (TransientDbFailure.isTransient(batchFailure) && backoff != null && retry < backoff.size()
							&& pause(backoff.get(retry))) {
						retry++;
						log.warn("{}: batch of {} rows from {} lost its database connection ({}), retry {} of {}", chainId,
								records.size(), source, batchFailure.toString(), retry, backoff.size());
						continue;
					}
					log.warn("{}: batch of {} rows from {} failed ({}), retrying row by row", chainId, records.size(), source,
							batchFailure.toString());
					break;
				}
			}
			boolean ok = true;
			for (int i = 0; i < records.size(); i++) {
				try {
					priceRecordRepository.save(records.get(i));
					priceStatsService.recordObservation(records.get(i));
					saved++;
				} catch (RuntimeException e) {
					if (TransientDbFailure.isTransient(e)) {
						// The connection is gone: each further row would wait for its own connection timeout (about 30 s).
						// Count the rest as failed at once; their category is retried on the next run.
						int rest = records.size() - i;
						for (int j = i; j < records.size(); j++) {
							seen.remove(chunkCodes.get(j));
						}
						failures += rest;
						log.error("{}: lost the database connection while storing rows of {} - the remaining {} row(s) of "
								+ "this batch are counted as failed without trying them ({} failure(s) so far): {}", chainId,
								source, rest, failures, e.toString());
						return false;
					}
					seen.remove(chunkCodes.get(i));     // not stored: a later category may carry it again
					failures++;
					ok = false;
					log.error("{}: storing row {} of {} failed ({} failure(s) so far), the crawl carries on: {}", chainId,
							records.get(i).getItemId(), source, failures, e.toString());
				}
			}
			return ok;
		}
	}

	/** Sleeps; false when interrupted (the interrupt flag is kept and retrying stops). */
	private static boolean pause(long millis) {
		if (millis <= 0) {
			return true;
		}
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Auckland day number, so each day takes a different window of a list longer than the daily budget. */
	private long dayNumber() {
		return clock.instant().atZone(ZoneId.of("Pacific/Auckland")).toLocalDate().toEpochDay();
	}

	/** At most {@code budget} URLs: the whole list when it fits, else a window that moves on with each run. */
	static List<String> selectCategories(List<String> urls, int budget, long runNumber) {
		if (budget <= 0 || urls.size() <= budget) {
			return urls;
		}
		int start = (int) ((runNumber * budget) % urls.size());
		List<String> window = new ArrayList<>(budget);
		for (int i = 0; i < budget; i++) {
			window.add(urls.get((start + i) % urls.size()));
		}
		return window;
	}

	private ChainConfig resolveChainConfig(ChainId chainId) {
		return switch (chainId) {
			case NEWWORLD -> properties.getNewworld();
			case PAKNSAVE -> properties.getPaknsave();
			case WOOLWORTHS -> properties.getWoolworths();
		};
	}
}
