package com.stocker.pricing.ingest;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;

public interface ChainScraper {

	ChainId chainId();

	ScrapeResult scrape(Browser browser, ChainScraperConfig config) throws ScrapeException;
}
