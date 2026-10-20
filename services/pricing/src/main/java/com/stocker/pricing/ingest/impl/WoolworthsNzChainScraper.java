package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.ScrapePacer;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.parse.ChainPageTemplate;
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
 * <p>The product-card selectors (in {@link ChainPageTemplate#woolworths}) are sourced directly from that reference scraper's
 * (working, live) implementation, not guessed - see INGEST_MODULE.md.
 *
 * <p>UNVERIFIED: no store-pin mechanism was found for this chain during research - a configured
 * {@code storeId} is currently NOT applied to the browser session at all, so results reflect
 * whatever store the site defaults to (its own IP-geolocation, most likely). See
 * INGEST_MODULE.md "Known Risks / Unverified".
 */
public class WoolworthsNzChainScraper implements ChainScraper {

	private static final Logger log = LoggerFactory.getLogger(WoolworthsNzChainScraper.class);

	private static final String DEFAULT_BASE_URL = "https://www.woolworths.co.nz";

	private final ChainPageTemplate template = ChainPageTemplate.woolworths();
	private final LeanPageOptions leanOptions;
	private final String baseUrl;
	private final PageCountMonitor pageCountMonitor = new PageCountMonitor();

	/** Lean page loads off (tests, live checks). */
	public WoolworthsNzChainScraper() {
		this(LeanPageOptions.OFF);
	}

	public WoolworthsNzChainScraper(LeanPageOptions leanOptions) {
		this(leanOptions, DEFAULT_BASE_URL);
	}

	/** @param baseUrl the chain's own site: its domain counts as first party and is never blocked by host pattern */
	public WoolworthsNzChainScraper(LeanPageOptions leanOptions, String baseUrl) {
		this.leanOptions = leanOptions;
		this.baseUrl = baseUrl;
	}

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

		LeanPageRouter router = new LeanPageRouter(ChainId.WOOLWORTHS, leanOptions, baseUrl);
		TemplatedPageLoader loader = new TemplatedPageLoader(ChainId.WOOLWORTHS, template, router, pageCountMonitor,
				leanOptions.logRequests());
		try (BrowserContext context = browser.newContext()) {
			router.install(context);
			Page page = context.newPage();
			page.setDefaultTimeout(config.getNavigationTimeoutSeconds() * 1000L);

			boolean firstPage = true;
			for (String categoryUrl : config.getCategoryUrls()) {
				if (config.getCancellation().isCancelled()) {
					break;      // the request that wanted these pages is gone: no further page load
				}
				if (config.getCrawlBrake().isOpen()) {
					warnings.add("Circuit breaker open: stopped before " + categoryUrl);
					log.warn("WOOLWORTHS: circuit breaker open, no further page loaded in this run");
					break;
				}
				if (!firstPage) {
					ScrapePacer.pauseBetweenPages(config);
					if (config.getCancellation().isCancelled()) {
						break;
					}
				}
				firstPage = false;
				try {
					List<ProductCardFields> cards = loader.load(page, categoryUrl, config.getCrawlBrake()).cards();
					visited.add(categoryUrl);
					if (cards.isEmpty()) {
						zeroResultCategoryUrls.add(categoryUrl);
						log.warn("WOOLWORTHS: zero product cards found at {} (template card selector {} may be stale, see ChainPageTemplate)",
								categoryUrl, template.cardSelector());
						continue;
					}
					WoolworthsProductCardExtractor.PageParse parsed = WoolworthsProductCardExtractor.parseAll(cards,
							config.getStoreId());
					products.addAll(parsed.products());
					String summary = parsed.summary(categoryUrl, template.describeSelectors());
					if (summary != null) {
						log.warn(summary);
					}
					if (parsed.templateStale()) {
						// The page itself loaded fine (BlockGuard passed) yet no tile gave a product: count it like a
						// zero-result page for CategoryHealth.
						zeroResultCategoryUrls.add(categoryUrl);
						log.warn("WOOLWORTHS: template stale or tiles not hydrated at {}: {} tiles found, 0 usable products "
								+ "(check ChainPageTemplate.woolworths() against the live page, see INGEST_MODULE.md)",
								categoryUrl, cards.size());
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
}
