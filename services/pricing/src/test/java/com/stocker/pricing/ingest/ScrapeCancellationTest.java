package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.ingest.impl.FoodstuffsChainScraper;
import com.stocker.pricing.ingest.impl.WoolworthsNzChainScraper;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A cancelled on-demand refresh stops after the page being loaded and never starts another (offline: Playwright is mocked). */
class ScrapeCancellationTest {

	private static final List<String> URLS = List.of("https://x/a", "https://x/b", "https://x/c", "https://x/d");

	private final AtomicInteger navigations = new AtomicInteger();
	private Browser browser;
	private Page page;

	private void mockBrowser(Runnable onNavigate) {
		browser = mock(Browser.class);
		BrowserContext context = mock(BrowserContext.class);
		page = mock(Page.class);
		Locator none = mock(Locator.class);
		when(browser.newContext()).thenReturn(context);
		when(context.newPage()).thenReturn(page);
		when(page.locator(anyString())).thenReturn(none);
		when(none.all()).thenReturn(List.of());
		when(page.navigate(anyString())).thenAnswer(invocation -> {
			navigations.incrementAndGet();
			onNavigate.run();
			return null;
		});
	}

	private static ChainScraperConfig config(CancellationToken token, long delayMillis) {
		return ChainScraperConfig.builder().storeId("").categoryUrls(URLS).navigationTimeoutSeconds(5)
				.pageDelayMinMillis(delayMillis).pageDelayMaxMillis(delayMillis).cancellation(token).build();
	}

	@Test
	void foodstuffsStopsAfterThePageThatWasLoadingWhenCancelled() {
		CancellationToken token = CancellationToken.create();
		mockBrowser(() -> {
			if (navigations.get() == 2) {
				token.cancel();
			}
		});

		ScrapeResult result = new FoodstuffsChainScraper(ChainId.NEWWORLD, "https://www.newworld.co.nz")
				.scrape(browser, config(token, 0));

		assertThat(navigations).hasValue(2);
		assertThat(result.getCategoriesVisited()).containsExactly("https://x/a", "https://x/b");
	}

	@Test
	void woolworthsStopsAfterThePageThatWasLoadingWhenCancelled() {
		CancellationToken token = CancellationToken.create();
		mockBrowser(() -> {
			if (navigations.get() == 1) {
				token.cancel();
			}
		});

		ScrapeResult result = new WoolworthsNzChainScraper()
				.scrape(browser, config(token, 0));

		assertThat(navigations).hasValue(1);
		assertThat(result.getCategoriesVisited()).containsExactly("https://x/a");
	}

	@Test
	void aTokenCancelledBeforeTheStartLoadsNothing() {
		CancellationToken token = CancellationToken.create();
		token.cancel();
		mockBrowser(() -> { });

		ScrapeResult result = new FoodstuffsChainScraper(ChainId.PAKNSAVE, "https://www.paknsave.co.nz")
				.scrape(browser, config(token, 0));

		assertThat(navigations).hasValue(0);
		assertThat(result.getCategoriesVisited()).isEmpty();
	}

	@Test
	void cancellingDuringThePacingPauseEndsThePauseAndLoadsNoFurtherPage() throws Exception {
		CancellationToken token = CancellationToken.create();
		mockBrowser(() -> {
			if (navigations.get() == 1) {
				new Thread(() -> {
					try {
						Thread.sleep(150);
					} catch (InterruptedException ignored) {
					}
					token.cancel();
				}).start();
			}
		});

		long start = System.nanoTime();
		ScrapeResult result = new FoodstuffsChainScraper(ChainId.NEWWORLD, "https://www.newworld.co.nz")
				.scrape(browser, config(token, 30_000));

		assertThat((System.nanoTime() - start) / 1_000_000).as("pause was cut short").isLessThan(5_000);
		assertThat(navigations).hasValue(1);
		assertThat(result.getCategoriesVisited()).containsExactly("https://x/a");
	}

	@Test
	void withoutATokenEveryPageIsLoadedAsBefore() {
		mockBrowser(() -> { });

		ScrapeResult result = new FoodstuffsChainScraper(ChainId.NEWWORLD, "https://www.newworld.co.nz")
				.scrape(browser, ChainScraperConfig.builder().storeId("").categoryUrls(URLS)
						.navigationTimeoutSeconds(5).build());

		assertThat(navigations).hasValue(4);
		assertThat(result.getCategoriesVisited()).isEqualTo(URLS);
	}

	@Test
	void pacerSleepIsInterruptedByCancel() {
		CancellationToken token = CancellationToken.create();
		new Thread(() -> {
			try {
				Thread.sleep(100);
			} catch (InterruptedException ignored) {
			}
			token.cancel();
		}).start();
		long start = System.nanoTime();
		ScrapePacer.pauseBetweenPages(config(token, 30_000));
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
	}

	@Test
	void aConfigWithoutATokenIsNeverCancelled() {
		assertThat(ChainScraperConfig.builder().build().getCancellation()).isSameAs(CancellationToken.NONE);
		assertThat(new ChainScraperConfig().getCancellation()).isSameAs(CancellationToken.NONE);
	}
}
