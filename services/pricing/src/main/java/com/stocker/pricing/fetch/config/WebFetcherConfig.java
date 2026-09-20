package com.stocker.pricing.fetch.config;

import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.config.WebFetcherProperties.WebFetcherProvider;
import com.stocker.pricing.fetch.impl.ScrapingBeeWebFetcher;
import com.stocker.pricing.fetch.impl.ScraperApiWebFetcher;
import com.stocker.pricing.fetch.impl.SerpApiWebFetcher;
import com.stocker.pricing.fetch.impl.SpreadWebFetcher;
import com.stocker.pricing.fetch.impl.RapidApiWebFetcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebFetcherConfig {

	@Bean
	@ConditionalOnProperty(name = "app.fetch.provider", havingValue = "spread", matchIfMissing = true)
	public WebFetcher spreadWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		return new SpreadWebFetcher(webClient, properties);
	}

	@Bean
	@ConditionalOnProperty(name = "app.fetch.provider", havingValue = "serpapi")
	public WebFetcher serpApiWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		return new SerpApiWebFetcher(webClient, properties);
	}

	@Bean
	@ConditionalOnProperty(name = "app.fetch.provider", havingValue = "scrapingbee")
	public WebFetcher scrapingBeeWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		return new ScrapingBeeWebFetcher(webClient, properties);
	}

	@Bean
	@ConditionalOnProperty(name = "app.fetch.provider", havingValue = "scraperapi")
	public WebFetcher scraperApiWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		return new ScraperApiWebFetcher(webClient, properties);
	}

	@Bean
	@ConditionalOnProperty(name = "app.fetch.provider", havingValue = "rapidapi")
	public WebFetcher rapidApiWebFetcher(WebClient webClient, WebFetcherProperties properties) {
		return new RapidApiWebFetcher(webClient, properties);
	}
}
