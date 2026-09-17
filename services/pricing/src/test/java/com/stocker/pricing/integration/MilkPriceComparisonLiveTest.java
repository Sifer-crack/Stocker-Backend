package com.stocker.pricing.integration;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.sitemap.SitemapCategoryDiscoveryService;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Live end-to-end test run: for each chain (New World, PAK'nSave, Woolworths NZ), discovers a
 * "milk" category page via {@link SitemapCategoryDiscoveryService} (exercising its two-tier
 * sitemap cache - Postgres-backed, 7-day TTL by default), scrapes it with that chain's existing
 * {@code ChainScraper}, and logs a per-chain price comparison for milk products.
 * <p>
 * Requires: Chromium installed ({@code ./gradlew :services:pricing:installPlaywrightBrowsers}),
 * a reachable Postgres (the sitemap cache's L2 tier, migrated by Flyway V2), and real network
 * access to newworld.co.nz / paknsave.co.nz / woolworths.co.nz. Disabled unless
 * {@code STOCKER_INGEST_MILK_LIVE_TEST=true}.
 * <p>
 * Selectors and the Foodstuffs store-pin cookies are still unverified against the live sites
 * (see INGEST_MODULE.md "Known Risks / Unverified") - a chain returning zero milk products here
 * is diagnostic signal to go fix that chain's scraper, not just a failing assertion, so this test
 * deliberately logs rather than hard-fails per chain.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_INGEST_MILK_LIVE_TEST", matches = "true")
class MilkPriceComparisonLiveTest {

	private static final Logger log = LoggerFactory.getLogger(MilkPriceComparisonLiveTest.class);
	private static final String KEYWORD = "milk";

	@Nested
	@SpringBootTest(properties = {
			"app.ingest.enabled=true",
			"spring.kafka.listener.auto-startup=false",
			"spring.kafka.bootstrap-servers=localhost:1"
	})
	class ComparesMilkPricesAcrossChains {

		@Autowired
		private List<ChainScraper> chainScrapers;

		@Autowired
		private Browser browser;

		@Autowired
		private SitemapCategoryDiscoveryService sitemapCategoryDiscoveryService;

		@Test
		void fetchesMilkPricesFromEveryChain() {
			assertFalse(chainScrapers.isEmpty(), "no ChainScraper beans wired - is app.ingest.enabled active?");

			for (ChainScraper scraper : chainScrapers) {
				List<String> categoryUrls = sitemapCategoryDiscoveryService.findCategoryUrls(scraper.chainId(), KEYWORD);
				if (categoryUrls.isEmpty()) {
					log.warn("{}: no '{}' category URL found via sitemap discovery, skipping", scraper.chainId(), KEYWORD);
					continue;
				}
				String categoryUrl = categoryUrls.get(0);
				log.info("{}: using category URL {}", scraper.chainId(), categoryUrl);

				ChainScraperConfig config = ChainScraperConfig.builder()
						.categoryUrls(List.of(categoryUrl))
						.maxPagesPerCategory(1)
						.navigationTimeoutSeconds(30)
						.build();

				ScrapeResult result = scraper.scrape(browser, config);
				List<RawProduct> milkProducts = result.getProducts().stream()
						.filter(p -> p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(KEYWORD))
						.toList();

				log.info("{}: {} product(s) scraped, {} matching '{}':", scraper.chainId(),
						result.getProducts().size(), milkProducts.size(), KEYWORD);
				milkProducts.forEach(p -> log.info("  {} {} - {}", p.getCurrency(), p.getPrice(), p.getName()));

				if (!result.getZeroResultCategoryUrls().isEmpty() || !result.getWarnings().isEmpty()) {
					log.warn("{}: zeroResultCategoryUrls={}, warnings={} - selectors may need correcting, see INGEST_MODULE.md",
							scraper.chainId(), result.getZeroResultCategoryUrls(), result.getWarnings());
				}
			}
		}
	}
}
