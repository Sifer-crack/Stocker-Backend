package com.stocker.pricing.integration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Cookie;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Live, manual-verification test for {@code ingest/impl/FoodstuffsChainScraper} against
 * New World. Not part of the regular test suite - disabled unless
 * {@code STOCKER_INGEST_NEWWORLD_LIVE_VERIFY=true}.
 * <p>
 * Uses Firefox, not Chromium - Chromium gets a Cloudflare Turnstile challenge on this site;
 * Firefox reaches it cleanly (verified directly). See
 * {@code ingest/config/PlaywrightConfig}'s javadoc and INGEST_MODULE.md.
 * <p>
 * The selector below (schema.org microdata) is verified against the live site. The store-pin
 * cookies remain an unverified hypothesis - this test's main purpose now is confirming that
 * hypothesis (does setting them change which products/prices come back?), not the selector
 * itself. See INGEST_MODULE.md "Known Risks / Unverified".
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_INGEST_NEWWORLD_LIVE_VERIFY", matches = "true")
class NewWorldPlaywrightIngestIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(NewWorldPlaywrightIngestIntegrationTest.class);

	private static final String DEFAULT_CATEGORY_URL =
			"https://www.newworld.co.nz/shop/category/fridge-deli-and-eggs/milk/fresh-milk?pg=1";
	private static final String PRODUCT_CARD_SELECTOR = "div[itemtype='https://schema.org/Product']";

	@Test
	void categoryPageYieldsProductCards() {
		String categoryUrl = System.getenv().getOrDefault("STOCKER_INGEST_NEWWORLD_LIVE_VERIFY_URL", DEFAULT_CATEGORY_URL);
		String storeId = System.getenv("STOCKER_INGEST_NEWWORLD_STORE_ID");

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
				if (storeId != null && !storeId.isBlank()) {
					context.addCookies(List.of(
							new Cookie("STORE_ID_V2", storeId).setDomain(".newworld.co.nz").setPath("/"),
							new Cookie("eCom_STORE_ID", storeId).setDomain(".newworld.co.nz").setPath("/")));
				} else {
					log.warn("STOCKER_INGEST_NEWWORLD_STORE_ID not set - site will fall back to IP-geolocated store");
				}

				Page page = context.newPage();
				page.navigate(categoryUrl);
				try {
					page.waitForSelector(PRODUCT_CARD_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(5000));
				} catch (com.microsoft.playwright.PlaywrightException ignored) {
					// Timeout is a legitimate "zero products" outcome - fall through to the assertion below.
				}
				List<Locator> cards = page.locator(PRODUCT_CARD_SELECTOR).all();

				log.info("NEWWORLD: {} product card(s) found at {} using selector '{}'",
						cards.size(), categoryUrl, PRODUCT_CARD_SELECTOR);
				if (!cards.isEmpty()) {
					log.info("NEWWORLD: first card outer HTML:\n{}", cards.get(0).evaluate("el => el.outerHTML"));
				}

				assertFalse(cards.isEmpty(), "No product cards found at " + categoryUrl
						+ " using selector '" + PRODUCT_CARD_SELECTOR + "' - the site's DOM structure has likely "
						+ "changed; update the selectors in FoodstuffsChainScraper");
			}
		}
	}
}
