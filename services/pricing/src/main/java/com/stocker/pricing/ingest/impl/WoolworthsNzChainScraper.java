package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.parse.ProductCardFields;
import com.stocker.pricing.ingest.parse.WoolworthsProductCardExtractor;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scrapes Woolworths NZ (woolworths.co.nz) category pages.
 *
 * <p>Uses Playwright's {@code firefox} browser, not {@code chromium} - see
 * {@link com.stocker.pricing.ingest.config.PlaywrightConfig}'s javadoc. Chromium fails
 * navigation outright with {@code net::ERR_HTTP2_PROTOCOL_ERROR} on this site; Firefox loads it
 * cleanly. This matches Jason-nzd/countdown-scraper, an actively-maintained reference scraper
 * for this exact site, which also uses playwright.firefox.
 *
 * <p>The product-card selectors below are sourced directly from that reference scraper's
 * (working, live) implementation, not guessed - see INGEST_MODULE.md.
 *
 * <p>UNVERIFIED: no store-pin mechanism was found for this chain during research - a configured
 * {@code storeId} is currently NOT applied to the browser session at all, so results reflect
 * whatever store the site defaults to (its own IP-geolocation, most likely). See
 * INGEST_MODULE.md "Known Risks / Unverified".
 */
public class WoolworthsNzChainScraper implements ChainScraper {

	private static final Logger log = LoggerFactory.getLogger(WoolworthsNzChainScraper.class);

	// Sourced from Jason-nzd/countdown-scraper's parser.ts/index.ts (live, working reference).
	private static final String PRODUCT_CARD_SELECTOR = "div[class^='product-tile_imgAndTextArea_']";
	private static final String PRODUCT_NAME_SELECTOR = "a[class^='product-tile_description_']";
	private static final String PRODUCT_PRICE_SELECTOR = "span[class^='product-price_value_']";
	private static final String PRODUCT_IMAGE_LINK_SELECTOR = "a[class^='product-tile_productImageLink_']";

	@Override
	public ChainId chainId() {
		return ChainId.WOOLWORTHS;
	}

	@Override
	public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
		List<RawProduct> products = new ArrayList<>();
		List<String> visited = new ArrayList<>();
		List<String> zeroResultCategoryUrls = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		if (config.getStoreId() != null && !config.getStoreId().isBlank()) {
			log.warn("WOOLWORTHS: storeId={} configured but no confirmed store-pin mechanism exists yet "
					+ "(see class javadoc) - it will NOT be applied to this session", config.getStoreId());
		}

		try (BrowserContext context = browser.newContext()) {
			Page page = context.newPage();
			page.setDefaultTimeout(config.getNavigationTimeoutSeconds() * 1000L);

			for (String categoryUrl : config.getCategoryUrls()) {
				try {
					page.navigate(categoryUrl);
					waitForProductCards(page);
					List<Locator> cards = page.locator(PRODUCT_CARD_SELECTOR).all();
					visited.add(categoryUrl);
					if (cards.isEmpty()) {
						zeroResultCategoryUrls.add(categoryUrl);
						log.warn("WOOLWORTHS: zero product cards found at {} (selector may be stale, see class javadoc)",
								categoryUrl);
						continue;
					}
					for (Locator card : cards) {
						WoolworthsProductCardExtractor.parse(extractFields(card), config.getStoreId())
								.ifPresent(products::add);
					}
				} catch (PlaywrightException e) {
					String warning = "Failed to scrape " + categoryUrl + ": " + e.getMessage();
					warnings.add(warning);
					log.warn("WOOLWORTHS: {}", warning);
				}
			}
		} catch (PlaywrightException e) {
			throw new ScrapeException("WOOLWORTHS scrape failed", e);
		}

		return ScrapeResult.builder()
				.chainId(ChainId.WOOLWORTHS)
				.products(products)
				.categoriesVisited(visited)
				.zeroResultCategoryUrls(zeroResultCategoryUrls)
				.warnings(warnings)
				.build();
	}

	/**
	 * The product grid renders client-side after the "load" event, so an immediate
	 * {@code locator(...).all()} can see zero cards even on a valid page. Wait briefly for the
	 * first card to appear; a timeout here is a legitimate "zero products" outcome, not an error.
	 */
	private void waitForProductCards(Page page) {
		try {
			page.waitForSelector(PRODUCT_CARD_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(5000));
		} catch (PlaywrightException e) {
			// No cards appeared in time - fall through, the caller's empty-list check handles it.
		}
	}

	private ProductCardFields extractFields(Locator card) {
		Locator imageLink = card.locator(PRODUCT_IMAGE_LINK_SELECTOR);
		return new ProductCardFields(
				textOrNull(card.locator(PRODUCT_NAME_SELECTOR)),
				null,
				textOrNull(card.locator(PRODUCT_PRICE_SELECTOR)),
				extractNativeCode(imageLink),
				attributeOrNull(imageLink.locator("img"), "src"),
				null);
	}

	/** Native code is derived from the product detail URL: second-to-last "/"-separated segment. */
	private String extractNativeCode(Locator imageLink) {
		String href = attributeOrNull(imageLink, "href");
		if (href == null) {
			return null;
		}
		String withoutQuery = href.contains("?") ? href.substring(0, href.indexOf('?')) : href;
		String[] segments = withoutQuery.split("/");
		return segments.length >= 2 ? segments[segments.length - 2] : null;
	}

	private String textOrNull(Locator locator) {
		try {
			return locator.count() > 0 ? locator.first().textContent() : null;
		} catch (PlaywrightException e) {
			return null;
		}
	}

	// count() > 0 guard is required, not optional: getAttribute() on a locator matching zero
	// elements auto-waits up to the page's full navigation timeout before giving up - fatally
	// slow when a card legitimately lacks the element, not an error.
	private String attributeOrNull(Locator locator, String attribute) {
		try {
			return locator.count() > 0 ? locator.first().getAttribute(attribute) : null;
		} catch (PlaywrightException e) {
			return null;
		}
	}
}
