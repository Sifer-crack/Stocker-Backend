package com.stocker.pricing.ingest.config;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
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

	@Bean(destroyMethod = "close")
	public Playwright playwright() {
		return Playwright.create();
	}

	@Bean(destroyMethod = "close")
	public Browser browser(Playwright playwright) {
		return playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
	}
}
