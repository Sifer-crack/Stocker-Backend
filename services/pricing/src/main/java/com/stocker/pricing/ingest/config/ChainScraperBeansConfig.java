package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.impl.FoodstuffsChainScraper;
import com.stocker.pricing.ingest.impl.WoolworthsNzChainScraper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.ingest.enabled", havingValue = "true")
public class ChainScraperBeansConfig {

	@Bean
	public ChainScraper newWorldChainScraper(IngestProperties properties) {
		return new FoodstuffsChainScraper(ChainId.NEWWORLD, "https://www.newworld.co.nz",
				properties.leanOptions(ChainId.NEWWORLD));
	}

	@Bean
	public ChainScraper paknsaveChainScraper(IngestProperties properties) {
		return new FoodstuffsChainScraper(ChainId.PAKNSAVE, "https://www.paknsave.co.nz",
				properties.leanOptions(ChainId.PAKNSAVE));
	}

	@Bean
	public ChainScraper woolworthsChainScraper(IngestProperties properties) {
		return new WoolworthsNzChainScraper(properties.leanOptions(ChainId.WOOLWORTHS));
	}
}
