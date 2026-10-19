package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.config.IngestProperties.ChainConfig;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
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
	private final ReentrantLock browserLock = new ReentrantLock(true);

	private final List<ChainScraper> chainScrapers;
	private final Browser browser;
	private final IngestProperties properties;
	private final PriceRecordRepository priceRecordRepository;
	private final PriceStatsService priceStatsService;
	private final DailyPageBudget pageBudget;
	private final CategoryMap categoryMap;
	private final Clock clock;
	private final CategoryHealth categoryHealth;

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
						Duration.ofDays(properties.getQuarantineDays()), clock));
	}

	public IngestScheduler(List<ChainScraper> chainScrapers, Browser browser, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService, CategoryMap categoryMap,
			Clock clock, CategoryHealth categoryHealth) {
		this.categoryHealth = categoryHealth;
		this.chainScrapers = chainScrapers;
		this.browser = browser;
		this.properties = properties;
		this.priceRecordRepository = priceRecordRepository;
		this.priceStatsService = priceStatsService;
		this.categoryMap = categoryMap;
		this.clock = clock;
		this.pageBudget = new DailyPageBudget(properties::getMaxPagesPerChainPerDay, clock);
	}

	@Scheduled(cron = "${app.ingest.cron}", zone = "${app.ingest.cron-zone:Pacific/Auckland}")
	public void runAll() {
		for (ChainScraper scraper : chainScrapers) {
			runOne(scraper);
		}
		List<CategoryHealth.QuarantinedPage> quarantined = categoryHealth.snapshot();
		if (!quarantined.isEmpty()) {
			log.warn("Quarantined category pages after this run ({}; in memory, a restart resets them): {}",
					quarantined.size(), quarantined);
		}
	}

	private void runOne(ChainScraper scraper) {
		ChainId chainId = scraper.chainId();
		ChainConfig chainConfig = resolveChainConfig(chainId);

		if (!chainConfig.isEnabled()) {
			log.info("{}: ingest disabled, skipping", chainId);
			return;
		}
		List<String> fullList = crawlList(chainId, chainConfig);
		// Quarantined pages are dropped BEFORE the budget window is chosen, so they take no slot in it.
		List<String> crawlList = withoutQuarantined(chainId, fullList);
		if (crawlList.isEmpty() && !fullList.isEmpty()) {
			log.info("{}: all {} category page(s) are quarantined, nothing to crawl", chainId, fullList.size());
			return;
		}
		if (crawlList.isEmpty()) {
			log.warn("{}: no category pages to crawl (see app.ingest.{}.category-urls and category-map.txt)",
					chainId, chainId.name().toLowerCase());
			return;
		}
		// Every page the catalog lists, within today's page budget; when the list is longer than the budget the
		// window moves on each day, so every page is refreshed within a few days.
		int window = properties.getMaxCategoriesPerRun() > 0
				? properties.getMaxCategoriesPerRun()
				: pageBudget.remaining(chainId);
		scrapeAndPersist(scraper, chainConfig, selectCategories(crawlList, window, dayNumber()));
	}

	/** The configured pages plus every page the catalog map lists for this chain, each once. */
	private List<String> crawlList(ChainId chainId, ChainConfig chainConfig) {
		Map<String, String> byPage = new LinkedHashMap<>();
		for (String url : chainConfig.getCategoryUrls()) {
			byPage.putIfAbsent(pageKey(url), url);
		}
		for (String url : categoryMap.urls(chainId)) {
			byPage.putIfAbsent(pageKey(url), url);
		}
		return List.copyOf(byPage.values());
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
		ChainConfig chainConfig = resolveChainConfig(chainId);
		if (!chainConfig.isEnabled() || categoryUrls.isEmpty()) {
			return 0;
		}
		List<String> pages = withoutQuarantined(chainId, categoryUrls);
		if (pages.isEmpty()) {
			return 0;
		}
		return chainScrapers.stream().filter(scraper -> scraper.chainId() == chainId).findFirst()
				.map(scraper -> scrapeAndPersist(scraper, chainConfig, pages)).orElse(0);
	}

	private int scrapeAndPersist(ChainScraper scraper, ChainConfig chainConfig, List<String> categoryUrls) {
		ChainId chainId = scraper.chainId();
		int granted = pageBudget.reserve(chainId, categoryUrls.size());
		if (granted == 0) {
			log.warn("{}: daily page budget ({} per day) used up, not loading {} page(s)", chainId,
					properties.getMaxPagesPerChainPerDay(), categoryUrls.size());
			return 0;
		}
		if (granted < categoryUrls.size()) {
			log.warn("{}: daily page budget leaves {} of {} requested page(s)", chainId, granted, categoryUrls.size());
			categoryUrls = categoryUrls.subList(0, granted);
		}
		ChainScraperConfig config = ChainScraperConfig.builder()
				.storeId(chainConfig.getStoreId())
				.storeName(chainConfig.getStoreName())
				.categoryUrls(categoryUrls)
				.maxPagesPerCategory(properties.getMaxPagesPerCategory())
				.navigationTimeoutSeconds(properties.getNavigationTimeoutSeconds())
				.pageDelayMinMillis(properties.getPageDelayMinMillis())
				.pageDelayMaxMillis(properties.getPageDelayMaxMillis())
				.build();

		ScrapeResult result;
		browserLock.lock();
		try {
			result = scraper.scrape(browser, config);
		} catch (ScrapeException e) {
			log.error("{}: scrape failed, skipping this run", chainId, e);
			return 0;
		} finally {
			browserLock.unlock();
		}

		pageBudget.giveBack(chainId, granted - result.getCategoriesVisited().size());   // reserved but never loaded
		categoryHealth.record(chainId, result);

		if (!result.getZeroResultCategoryUrls().isEmpty()) {
			log.warn("{}: {} of {} category page(s) returned zero products: {}", chainId,
					result.getZeroResultCategoryUrls().size(), result.getCategoriesVisited().size(),
					result.getZeroResultCategoryUrls());
		}
		if (!result.getWarnings().isEmpty()) {
			log.warn("{}: {} warning(s) during scrape: {}", chainId, result.getWarnings().size(), result.getWarnings());
		}

		List<RawProduct> deduped = dedupeByNativeProductCode(result.getProducts());
		log.info("{}: scraped {} products ({} after de-dupe) from {} categor{}", chainId, result.getProducts().size(),
				deduped.size(), result.getCategoriesVisited().size(), result.getCategoriesVisited().size() == 1 ? "y" : "ies");

		if (properties.isDryRun()) {
			log.info("{}: dry-run enabled, not persisting", chainId);
			return 0;
		}

		int saved = 0;
		for (RawProduct product : deduped) {
			try {
				PriceRecord record = RawProductPriceRecordMapper.toPriceRecord(product);
				priceRecordRepository.save(record);
				priceStatsService.recordObservation(record);
				saved++;
			} catch (IllegalArgumentException e) {
				log.warn("{}: skipping product, could not derive itemId: {}", chainId, e.getMessage());
			}
		}
		log.info("{}: persisted {} price records", chainId, saved);
		return saved;
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

	private List<RawProduct> dedupeByNativeProductCode(List<RawProduct> products) {
		Map<String, RawProduct> byCode = new LinkedHashMap<>();
		for (RawProduct product : products) {
			byCode.putIfAbsent(product.getNativeProductCode(), product);
		}
		return List.copyOf(byCode.values());
	}
}
