package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.ScrapePacer;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.CrawlBrake;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.ingest.parse.FoodstuffsProductCardExtractor;
import com.stocker.pricing.ingest.parse.ChainPageTemplate;
import com.stocker.pricing.ingest.parse.ProductCardFields;
import com.stocker.pricing.ingest.impl.PaginatedCategoryWalker.LoadedPage;
import java.util.ArrayList;
import java.util.List;
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
 * <p>The product-card selectors (schema.org microdata + {@code data-testid}) live in {@link ChainPageTemplate#foodstuffs};
 * they are verified against the live site (captured real DOM, see INGEST_MODULE.md). The store-pin cookie names
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

	private final ChainId chainId;
	private final String baseUrl;
	private final ChainPageTemplate template;
	private final LeanPageOptions leanOptions;
	private final PageCountMonitor pageCountMonitor = new PageCountMonitor();

	/** Lean page loads off (tests, live checks). */
	public FoodstuffsChainScraper(ChainId chainId, String baseUrl) {
		this(chainId, baseUrl, LeanPageOptions.OFF);
	}

	public FoodstuffsChainScraper(ChainId chainId, String baseUrl, LeanPageOptions leanOptions) {
		this(chainId, baseUrl, leanOptions, ChainPageTemplate.foodstuffs(chainId));
	}

	/** @param template the page template; pass a {@code withField}-adjusted copy for chain-specific differences */
	public FoodstuffsChainScraper(ChainId chainId, String baseUrl, LeanPageOptions leanOptions,
			ChainPageTemplate template) {
		if (chainId != ChainId.NEWWORLD && chainId != ChainId.PAKNSAVE) {
			throw new IllegalArgumentException("FoodstuffsChainScraper only supports NEWWORLD/PAKNSAVE, got " + chainId);
		}
		this.chainId = chainId;
		this.baseUrl = baseUrl;
		this.leanOptions = leanOptions;
		this.template = template;
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

		LeanPageRouter router = new LeanPageRouter(chainId, leanOptions, baseUrl);
		TemplatedPageLoader loader = new TemplatedPageLoader(chainId, template, router, pageCountMonitor,
				leanOptions.logRequests());
		try (BrowserContext context = browser.newContext()) {
			router.install(context);
			applyStorePin(context, config.getStoreId());
			Page page = context.newPage();
			page.setDefaultTimeout(config.getNavigationTimeoutSeconds() * 1000L);

			boolean firstPage = true;
			for (String categoryUrl : config.getCategoryUrls()) {
				if (config.getCancellation().isCancelled()) {
					break;      // the request that wanted these pages is gone: no further page load
				}
				if (config.getCrawlBrake().isOpen()) {
					warnings.add("Circuit breaker open: stopped before " + categoryUrl);
					log.warn("{}: circuit breaker open, no further page loaded in this run", chainId);
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
					PaginatedCategoryWalker<ProductCardFields> walker = new PaginatedCategoryWalker<>(
							config.getMaxPagesPerCategory(), config.getExtraPageGate(),
							() -> ScrapePacer.pauseBetweenPages(config), () -> config.getCancellation().isCancelled());
					PaginatedCategoryWalker.Walk<ProductCardFields> walk = walker.walk(categoryUrl, url -> loadPage(loader, page, url, config.getCrawlBrake()));
					visited.add(categoryUrl);
					if (walk.items().isEmpty()) {
						zeroResultCategoryUrls.add(categoryUrl);
						log.warn("{}: zero product cards found at {} (template card selector {} may be stale, see ChainPageTemplate)",
								chainId, categoryUrl, template.cardSelector());
						continue;
					}
					List<RawProduct> categoryProducts = new ArrayList<>();
					for (ProductCardFields fields : walk.items()) {
						FoodstuffsProductCardExtractor.parse(fields, chainId, config.getStoreId()).ifPresent(categoryProducts::add);
					}
					products.addAll(categoryProducts);
					config.getCategoryResultSink().onCategory(categoryUrl, List.copyOf(categoryProducts));
					if (walk.extraPages() > 0) {
						log.info("{}: {} had {} product(s) over {} pages", chainId, categoryUrl, walk.items().size(),
								walk.pagesLoaded());
					}
					if (walk.warning() != null) {
						warnings.add(walk.warning());
						log.warn("{}: {}", chainId, walk.warning());
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

	/** Loads one category page through the template: its tiles plus the "Showing A - B of M" line. */
	private LoadedPage<ProductCardFields> loadPage(TemplatedPageLoader loader, Page page, String url, CrawlBrake brake) {
		TemplatedPageLoader.PageRead read = loader.load(page, url, brake);
		return new LoadedPage<>(read.cards(), read.showingText() == null ? "" : read.showingText());
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
}
