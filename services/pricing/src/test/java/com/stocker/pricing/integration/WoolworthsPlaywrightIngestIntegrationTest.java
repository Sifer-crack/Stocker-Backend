package com.stocker.pricing.integration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live, manual-verification test for {@code ingest/impl/WoolworthsNzChainScraper}. Not part of
 * the regular test suite - disabled unless {@code STOCKER_INGEST_WOOLWORTHS_LIVE_VERIFY=true}.
 * <p>
 * Uses Firefox, not Chromium - Chromium fails navigation outright with
 * {@code net::ERR_HTTP2_PROTOCOL_ERROR} on this site; Firefox loads it cleanly (verified
 * directly). See {@code ingest/config/PlaywrightConfig}'s javadoc and INGEST_MODULE.md.
 * <p>
 * The selector below is sourced from Jason-nzd/countdown-scraper's live, working
 * implementation for this exact site, not guessed - no store-pin is applied, since none is
 * confirmed for this chain yet (see {@code WoolworthsNzChainScraper}'s class javadoc). See
 * INGEST_MODULE.md "Known Risks / Unverified".
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_INGEST_WOOLWORTHS_LIVE_VERIFY", matches = "true")
class WoolworthsPlaywrightIngestIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(WoolworthsPlaywrightIngestIntegrationTest.class);

	private static final String DEFAULT_CATEGORY_URL =
			"https://www.woolworths.co.nz/shop/browse/fridge-deli/milk/full-cream-milk";
	private static final String PRODUCT_CARD_SELECTOR = "div[class^='product-tile_imgAndTextArea_']";

	@Test
	void categoryPageYieldsProductCards() {
		String categoryUrl = System.getenv().getOrDefault("STOCKER_INGEST_WOOLWORTHS_LIVE_VERIFY_URL", DEFAULT_CATEGORY_URL);

		Playwright playwright;
		try {
			playwright = Playwright.create();
		} catch (RuntimeException e) {
			fail("Playwright driver/browser not available - run "
					+ "'./gradlew :services:pricing:installPlaywrightBrowsers' first", e);
			return;
		}

		try (playwright; Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
			try (BrowserContext context = browser.newContext()) {
				Page page = context.newPage();
				page.navigate(categoryUrl);
				try {
					page.waitForSelector(PRODUCT_CARD_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(5000));
				} catch (com.microsoft.playwright.PlaywrightException ignored) {
					// Timeout is a legitimate "zero products" outcome - fall through to the assertion below.
				}
				List<Locator> cards = page.locator(PRODUCT_CARD_SELECTOR).all();

				log.info("WOOLWORTHS: {} product card(s) found at {} using selector '{}'",
						cards.size(), categoryUrl, PRODUCT_CARD_SELECTOR);
				if (!cards.isEmpty()) {
					log.info("WOOLWORTHS: first card outer HTML:\n{}", cards.get(0).evaluate("el => el.outerHTML"));
				}

				assertFalse(cards.isEmpty(), "No product cards found at " + categoryUrl
						+ " using selector '" + PRODUCT_CARD_SELECTOR + "' - the site's DOM structure has likely "
						+ "changed; update the selectors in WoolworthsNzChainScraper");
			}
		}
	}
}
