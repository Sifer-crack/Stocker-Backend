package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.parse.FoodstuffsProductCardExtractor;
import com.stocker.pricing.ingest.parse.ProductCardFields;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scrapes New World and PAK'nSave - two banners of the same Foodstuffs/Sitecore platform,
 * differing only in domain, chainId and store-pin cookie value.
 *
 * <p>Uses Playwright's {@code firefox} browser, not {@code chromium} - see
 * {@link com.stocker.pricing.ingest.config.PlaywrightConfig}'s javadoc. Chromium gets a
 * Cloudflare Turnstile challenge on both banners; Firefox reaches them cleanly.
 *
 * <p>The product-card selectors below (schema.org microdata + {@code data-testid}) are verified
 * against the live site (captured real DOM, see INGEST_MODULE.md). The store-pin cookie names
 * ({@link #STORE_ID_COOKIE}, {@link #ECOM_STORE_ID_COOKIE}) remain an UNVERIFIED hypothesis -
 * store selection was not exercised when the selectors above were confirmed. Run the gated
 * live-verification integration test for this chain to confirm/correct the cookie behavior
 * before relying on per-store pricing. See INGEST_MODULE.md "Known Risks / Unverified".
 */
public class FoodstuffsChainScraper implements ChainScraper {

	private static final Logger log = LoggerFactory.getLogger(FoodstuffsChainScraper.class);

	// UNVERIFIED - see class javadoc.
	private static final String STORE_ID_COOKIE = "STORE_ID_V2";
	private static final String ECOM_STORE_ID_COOKIE = "eCom_STORE_ID";

	// Verified against the live site (New World + PAK'nSave, see INGEST_MODULE.md).
	private static final String PRODUCT_CARD_SELECTOR = "div[itemtype='https://schema.org/Product']";
	private static final String PRODUCT_NAME_SELECTOR = "[data-testid='product-title']";
	private static final String PRODUCT_SUBTITLE_SELECTOR = "[data-testid='product-subtitle']";
	private static final String PRODUCT_PRICE_META_SELECTOR = "meta[itemprop='price']";
	// Fallback for banners that omit the schema.org price meta tag (confirmed on PAK'nSave,
	// which otherwise has identical markup to New World) - split dollar/cents text spans.
	private static final String PRODUCT_PRICE_DOLLARS_SELECTOR = "[data-testid='price-dollars']";
	private static final String PRODUCT_PRICE_CENTS_SELECTOR = "[data-testid='price-cents']";
	private static final String PRODUCT_IMAGE_SELECTOR = "[data-testid='product-image']";
	private static final String CARD_TESTID_ATTRIBUTE = "data-testid";
	// Card data-testid looks like "product-5000518-EA-000" - the digits are the native code.
	private static final Pattern NATIVE_CODE_PATTERN = Pattern.compile("product-(\\d+)-\\w+-\\d+");

	private final ChainId chainId;
	private final String baseUrl;

	public FoodstuffsChainScraper(ChainId chainId, String baseUrl) {
		if (chainId != ChainId.NEWWORLD && chainId != ChainId.PAKNSAVE) {
			throw new IllegalArgumentException("FoodstuffsChainScraper only supports NEWWORLD/PAKNSAVE, got " + chainId);
		}
		this.chainId = chainId;
		this.baseUrl = baseUrl;
	}

	@Override
	public ChainId chainId() {
		return chainId;
	}

	@Override
	public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
		List<RawProduct> products = new ArrayList<>();
		List<String> visited = new ArrayList<>();
		List<String> zeroResultCategoryUrls = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		try (BrowserContext context = browser.newContext()) {
			applyStorePin(context, config.getStoreId());
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
						log.warn("{}: zero product cards found at {} (selector may be stale, see class javadoc)",
								chainId, categoryUrl);
						continue;
					}
					for (Locator card : cards) {
						FoodstuffsProductCardExtractor.parse(extractFields(card), chainId, config.getStoreId())
								.ifPresent(products::add);
					}
				} catch (PlaywrightException e) {
					String warning = "Failed to scrape " + categoryUrl + ": " + e.getMessage();
					warnings.add(warning);
					log.warn("{}: {}", chainId, warning);
				}
			}
		} catch (PlaywrightException e) {
			throw new ScrapeException(chainId + " scrape failed", e);
		}

		return ScrapeResult.builder()
				.chainId(chainId)
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

	/** UNVERIFIED store-pin hypothesis - see class javadoc. */
	private void applyStorePin(BrowserContext context, String storeId) {
		if (storeId == null || storeId.isBlank()) {
			log.warn("{}: no storeId configured, site will fall back to IP-geolocated store", chainId);
			return;
		}
		String domain = baseUrl.replaceFirst("^https?://(www\\.)?", "");
		context.addCookies(List.of(
				new Cookie(STORE_ID_COOKIE, storeId).setDomain("." + domain).setPath("/"),
				new Cookie(ECOM_STORE_ID_COOKIE, storeId).setDomain("." + domain).setPath("/")));
	}

	private ProductCardFields extractFields(Locator card) {
		return new ProductCardFields(
				textOrNull(card.locator(PRODUCT_NAME_SELECTOR)),
				textOrNull(card.locator(PRODUCT_SUBTITLE_SELECTOR)),
				extractPriceText(card),
				extractNativeCode(card),
				attributeOrNull(card.locator(PRODUCT_IMAGE_SELECTOR), "src"),
				null);
	}

	private String extractPriceText(Locator card) {
		String metaPrice = attributeOrNull(card.locator(PRODUCT_PRICE_META_SELECTOR), "content");
		if (metaPrice != null && !metaPrice.isBlank()) {
			return metaPrice;
		}
		String dollars = textOrNull(card.locator(PRODUCT_PRICE_DOLLARS_SELECTOR));
		String cents = textOrNull(card.locator(PRODUCT_PRICE_CENTS_SELECTOR));
		return dollars != null && cents != null ? dollars.trim() + "." + cents.trim() : null;
	}

	private String extractNativeCode(Locator card) {
		String testId = attributeOrNull(card, CARD_TESTID_ATTRIBUTE);
		if (testId == null) {
			return null;
		}
		Matcher matcher = NATIVE_CODE_PATTERN.matcher(testId);
		return matcher.matches() ? matcher.group(1) : null;
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
	// slow when a card legitimately lacks the element (e.g. no price shown), not an error.
	private String attributeOrNull(Locator locator, String attribute) {
		try {
			return locator.count() > 0 ? locator.first().getAttribute(attribute) : null;
		} catch (PlaywrightException e) {
			return null;
		}
	}
}
