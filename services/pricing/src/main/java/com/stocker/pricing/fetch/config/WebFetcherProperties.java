package com.stocker.pricing.fetch.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.fetch")
public class WebFetcherProperties {

	private WebFetcherProvider provider = WebFetcherProvider.SPREAD;
	private int timeoutSeconds = 30;
	private SpreadConfig spread = new SpreadConfig();
	private SerpApiConfig serpapi = new SerpApiConfig();
	private ScrapingBeeConfig scrapingbee = new ScrapingBeeConfig();
	private ScraperApiConfig scraperapi = new ScraperApiConfig();
	private RapidApiConfig rapidapi = new RapidApiConfig();
	private List<CultureStoreConfig> cultureStores = List.of();

	public enum WebFetcherProvider {
		SPREAD, SERPAPI, SCRAPINGBEE, SCRAPERAPI, RAPIDAPI
	}

	@Getter
	@Setter
	public static class SpreadConfig {
		private String baseUrl = "https://spread.butterup.app/spread-api/v1";
		private String apiKey = "";
	}

	@Getter
	@Setter
	public static class SerpApiConfig {
		private String baseUrl = "https://serpapi.com/search";
		private String apiKey = "";
		private String country = "nz";
	}

	@Getter
	@Setter
	public static class ScrapingBeeConfig {
		private String baseUrl = "https://app.scrapingbee.com/api/v1";
		private String apiKey = "";
	}

	@Getter
	@Setter
	public static class ScraperApiConfig {
		private String baseUrl = "https://api.scraperapi.com";
		private String apiKey = "";
	}

	@Getter
	@Setter
	public static class RapidApiConfig {
		private String baseUrl = "https://woolworths-products-api.p.rapidapi.com";
		private String host = "woolworths-products-api.p.rapidapi.com";
		private String endpoint = "/woolworths/product-search/";
		private String priceChangesEndpoint = "/woolworths/price-changes/";
		private String apiKey = "";
		private String queryParam = "query";
		private int limit = 50;
		private String currency = "AUD";
	}

	@Getter
	@Setter
	public static class CultureStoreConfig {
		private String name = "";
		private String url = "";
		private String chain = "";
	}
}
