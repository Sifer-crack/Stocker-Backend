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
	public ChainScraper newWorldChainScraper() {
		return new FoodstuffsChainScraper(ChainId.NEWWORLD, "https://www.newworld.co.nz");
	}

	@Bean
	public ChainScraper paknsaveChainScraper() {
		return new FoodstuffsChainScraper(ChainId.PAKNSAVE, "https://www.paknsave.co.nz");
	}

	@Bean
	public ChainScraper woolworthsChainScraper() {
		return new WoolworthsNzChainScraper();
	}
}
