package com.stocker.pricing.ingest.config;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.CategoryHealth;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.sitemap.SitemapCategoryDiscoveryService;
import com.stocker.pricing.ingest.sitemap.StapleCategoryMatcher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.ingest.enabled", havingValue = "true")
public class IngestSchedulerConfig {

	@Bean
	public IngestScheduler ingestScheduler(List<ChainScraper> chainScrapers, Browser browser,
			IngestProperties properties, PriceRecordRepository priceRecordRepository,
			PriceStatsService priceStatsService, CategoryMap categoryMap,
			ObjectProvider<SitemapCategoryDiscoveryService> sitemapProvider) {
		Clock clock = Clock.systemUTC();
		CategoryHealth categoryHealth = new CategoryHealth(properties.getQuarantineAfterZeroRuns(),
				Duration.ofDays(properties.getQuarantineDays()), properties.getQuarantineSiteWideZeroShare(), clock);
		return new IngestScheduler(chainScrapers, browser, properties, priceRecordRepository, priceStatsService,
				categoryMap, clock, categoryHealth, stapleDiscovery(sitemapProvider, properties));
	}

	/**
	 * Staple category pages from the chain's sitemap (cached for sitemap-cache-ttl-days, so at most a few sitemap
	 * downloads a week). Added to the crawl without checking them first: a dead page is quarantined by CategoryHealth.
	 */
	static Function<ChainId, List<String>> stapleDiscovery(ObjectProvider<SitemapCategoryDiscoveryService> sitemapProvider,
			IngestProperties properties) {
		Set<String> keywords = StapleCategoryMatcher.loadKeywords();
		return chain -> {
			SitemapCategoryDiscoveryService sitemap = sitemapProvider.getIfAvailable();
			return sitemap == null ? List.of()
					: StapleCategoryMatcher.select(sitemap.allCategoryUrls(chain), keywords,
							properties.getMaxDiscoveredPagesPerChain());
		};
	}

	/** Seeds fresh data: one background ingest run after startup when app.ingest.run-on-startup=true. */
	@Bean
	public ApplicationRunner ingestOnStartup(IngestScheduler scheduler, IngestProperties properties) {
		return args -> {
			if (!properties.isRunOnStartup()) {
				return;
			}
			Thread thread = new Thread(scheduler::runAll, "ingest-on-startup");
			thread.setDaemon(true);   // a multi-minute paced scrape must never keep the JVM alive after a stop signal
			thread.start();
		};
	}
}
