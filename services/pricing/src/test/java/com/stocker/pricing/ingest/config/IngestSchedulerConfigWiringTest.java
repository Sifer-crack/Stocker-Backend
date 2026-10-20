package com.stocker.pricing.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.ingest.ChainBrowsers;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.sitemap.SitemapCategoryDiscoveryService;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** The Spring-built scheduler carries the on-demand limits and the own-domain rule. */
class IngestSchedulerConfigWiringTest {

	@SuppressWarnings("unchecked")
	@Test
	void theBeanAppliesTheOnDemandLimitsAndTheDomainRule() {
		List<ChainScraperConfig> configs = new ArrayList<>();
		ChainScraper scraper = new ChainScraper() {
			public ChainId chainId() {
				return ChainId.NEWWORLD;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				configs.add(config);
				return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of())
						.categoriesVisited(config.getCategoryUrls()).zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
		IngestProperties properties = new IngestProperties();
		properties.setDryRun(true);
		properties.getNewworld().setEnabled(true);
		PricingCacheProperties cache = new PricingCacheProperties();
		cache.getOnDemand().setMaxPagesPerCategory(1);
		cache.getOnDemand().setKeepDailyBudgetShare(0.5);
		ObjectProvider<SitemapCategoryDiscoveryService> noSitemap = mock(ObjectProvider.class);

		IngestScheduler scheduler = new IngestSchedulerConfig().ingestScheduler(List.of(scraper),
				ChainBrowsers.shared(mock(Browser.class)), properties, mock(PriceRecordRepository.class),
				mock(PriceStatsService.class), CategoryMap.empty(), noSitemap, cache);

		scheduler.refreshCategories(ChainId.NEWWORLD,
				List.of("https://www.newworld.co.nz/shop/category/milk", "https://evil.example/x"), CancellationToken.NONE);

		assertThat(configs).hasSize(1);
		assertThat(configs.get(0).getCategoryUrls()).containsExactly("https://www.newworld.co.nz/shop/category/milk");
		assertThat(configs.get(0).getMaxPagesPerCategory()).isEqualTo(1);
	}
}
