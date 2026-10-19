package com.stocker.pricing.ingest.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.stocker.pricing.ingest.ChainBrowsers;
import com.stocker.pricing.ingest.ManagedBrowser;
import com.stocker.pricing.ingest.PerChainBrowsers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Launches a single shared headless Firefox instance for the ingest/ module. Gated by
 * app.ingest.enabled so Playwright.create() (which spawns a driver process) never runs, and no
 * environment needs Firefox installed, unless a deployment explicitly opts in.
 *
 * <p>Firefox, not Chromium: verified directly against the live sites (see INGEST_MODULE.md
 * "Known Risks / Unverified"). Chromium gets a Cloudflare Turnstile challenge on New
 * World/PAK'nSave and a net::ERR_HTTP2_PROTOCOL_ERROR on Woolworths NZ; Firefox reaches all
 * three cleanly. This matches Jason-nzd/countdown-scraper, the actively-maintained reference
 * scraper for Woolworths/Countdown NZ, which also uses playwright.firefox.
 */
@Configuration
@ConditionalOnProperty(name = "app.ingest.enabled", havingValue = "true")
public class PlaywrightConfig {

	/** Default: one Playwright and one Firefox shared by the three chains, which crawl one after another. */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "app.ingest.parallel-chains", havingValue = "false", matchIfMissing = true)
	public Playwright playwright() {
		return Playwright.create();
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "app.ingest.parallel-chains", havingValue = "false", matchIfMissing = true)
	public Browser browser(Playwright playwright) {
		return launch(playwright);
	}

	@Bean
	@ConditionalOnProperty(name = "app.ingest.parallel-chains", havingValue = "false", matchIfMissing = true)
	public ChainBrowsers sharedChainBrowsers(Browser browser) {
		return ChainBrowsers.shared(browser);
	}

	/**
	 * app.ingest.parallel-chains=true: no shared browser is started; each chain starts its own Playwright driver and
	 * Firefox on first use, and Spring closes all of them on shutdown.
	 */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "app.ingest.parallel-chains", havingValue = "true")
	public ChainBrowsers perChainBrowsers() {
		return new PerChainBrowsers(PlaywrightConfig::launchOwned);
	}

	public static ManagedBrowser launchOwned() {
		Playwright playwright = Playwright.create();
		Browser browser;
		try {
			browser = launch(playwright);
		} catch (RuntimeException e) {
			playwright.close();   // no orphaned driver process when Firefox fails to start
			throw e;
		}
		return new ManagedBrowser() {
			@Override
			public Browser browser() {
				return browser;
			}

			@Override
			public void close() {
				try {
					browser.close();
				} finally {
					playwright.close();
				}
			}
		};
	}

	private static Browser launch(Playwright playwright) {
		return playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
	}
}
