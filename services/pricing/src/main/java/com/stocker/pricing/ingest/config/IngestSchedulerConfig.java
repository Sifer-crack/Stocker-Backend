package com.stocker.pricing.ingest.config;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.ingest.enabled", havingValue = "true")
public class IngestSchedulerConfig {

	@Bean
	public IngestScheduler ingestScheduler(List<ChainScraper> chainScrapers, Browser browser,
			IngestProperties properties, PriceRecordRepository priceRecordRepository) {
		return new IngestScheduler(chainScrapers, browser, properties, priceRecordRepository);
	}
}
