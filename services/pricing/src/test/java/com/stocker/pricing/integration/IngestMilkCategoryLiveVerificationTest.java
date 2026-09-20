package com.stocker.pricing.integration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.config.IngestProperties.ChainConfig;
import com.stocker.pricing.ingest.impl.FoodstuffsChainScraper;
import com.stocker.pricing.ingest.impl.WoolworthsNzChainScraper;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Live, manual-verification test for the real ingest pipeline (IngestScheduler driving the real
 * FoodstuffsChainScraper/WoolworthsNzChainScraper, not stubs) against the exact milk
 * category-urls configured in application.yml, in dry-run mode. Unlike the per-chain
 * *PlaywrightIngestIntegrationTest classes (which check raw selectors against a hardcoded URL),
 * this exercises the production orchestration path end-to-end - the thing that actually needs to
 * work before app.ingest.dry-run is ever flipped to false. Not part of the regular test suite -
 * disabled unless STOCKER_INGEST_MILK_LIVE_VERIFY=true. Check this test's own log output
 * (per-chain product counts, zeroResultCategoryUrls, warnings) for the actual verification -
 * see IngestScheduler.runOne.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_INGEST_MILK_LIVE_VERIFY", matches = "true")
class IngestMilkCategoryLiveVerificationTest {

	@Test
	void dryRunAcrossAllThreeChainsScrapesWithoutPersisting() {
		Playwright playwright;
		try {
			playwright = Playwright.create();
		} catch (RuntimeException e) {
			fail("Playwright driver/browser not available - run "
					+ "'./gradlew :services:pricing:installPlaywrightBrowsers' first", e);
			return;
		}

		try (playwright;
				Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
			List<ChainScraper> scrapers = List.of(
					new FoodstuffsChainScraper(ChainId.NEWWORLD, "https://www.newworld.co.nz"),
					new FoodstuffsChainScraper(ChainId.PAKNSAVE, "https://www.paknsave.co.nz"),
					new WoolworthsNzChainScraper());

			IngestProperties properties = new IngestProperties();
			properties.setDryRun(true);
			properties.setMaxPagesPerCategory(5);
			properties.setNavigationTimeoutSeconds(30);
			properties.setNewworld(chainConfig(
					"https://www.newworld.co.nz/shop/category/fridge-deli-and-eggs/milk/fresh-milk?pg=1"));
			properties.setPaknsave(chainConfig(
					"https://www.paknsave.co.nz/shop/category/fridge-deli-and-eggs/milk/fresh-milk?pg=1"));
			properties.setWoolworths(
					chainConfig("https://www.woolworths.co.nz/shop/browse/fridge-deli/milk/full-cream-milk"));

			PriceRecordRepository repository = mock(PriceRecordRepository.class);
			PriceStatsService priceStatsService = mock(PriceStatsService.class);
			IngestScheduler scheduler = new IngestScheduler(scrapers, browser, properties, repository, priceStatsService);

			scheduler.runAll();

			verify(repository, never()).save(any(PriceRecord.class));
		}
	}

	private static ChainConfig chainConfig(String categoryUrl) {
		ChainConfig config = new ChainConfig();
		config.setEnabled(true);
		config.setCategoryUrls(List.of(categoryUrl));
		return config;
	}
}
