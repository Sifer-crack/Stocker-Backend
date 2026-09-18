package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.config.IngestProperties.ChainConfig;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.RawProductPriceRecordMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	private final List<ChainScraper> chainScrapers;
	private final Browser browser;
	private final IngestProperties properties;
	private final PriceRecordRepository priceRecordRepository;
	private final PriceStatsService priceStatsService;

	public IngestScheduler(List<ChainScraper> chainScrapers, Browser browser, IngestProperties properties,
			PriceRecordRepository priceRecordRepository, PriceStatsService priceStatsService) {
		this.chainScrapers = chainScrapers;
		this.browser = browser;
		this.properties = properties;
		this.priceRecordRepository = priceRecordRepository;
		this.priceStatsService = priceStatsService;
	}

	@Scheduled(cron = "${app.ingest.cron}")
	public void runAll() {
		for (ChainScraper scraper : chainScrapers) {
			runOne(scraper);
		}
	}

	private void runOne(ChainScraper scraper) {
		ChainId chainId = scraper.chainId();
		ChainConfig chainConfig = resolveChainConfig(chainId);

		if (!chainConfig.isEnabled()) {
			log.info("{}: ingest disabled, skipping", chainId);
			return;
		}
		if (chainConfig.getCategoryUrls().isEmpty()) {
			log.warn("{}: no category-urls configured, skipping (see app.ingest.{}.category-urls)",
					chainId, chainId.name().toLowerCase());
			return;
		}

		ChainScraperConfig config = ChainScraperConfig.builder()
				.storeId(chainConfig.getStoreId())
				.storeName(chainConfig.getStoreName())
				.categoryUrls(chainConfig.getCategoryUrls())
				.maxPagesPerCategory(properties.getMaxPagesPerCategory())
				.navigationTimeoutSeconds(properties.getNavigationTimeoutSeconds())
				.build();

		ScrapeResult result;
		try {
			result = scraper.scrape(browser, config);
		} catch (ScrapeException e) {
			log.error("{}: scrape failed, skipping this run", chainId, e);
			return;
		}

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
			return;
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
