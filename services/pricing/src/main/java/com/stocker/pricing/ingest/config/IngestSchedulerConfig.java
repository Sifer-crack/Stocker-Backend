package com.stocker.pricing.ingest.config;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.ingest.enabled", havingValue = "true")
public class IngestSchedulerConfig {

	@Bean
	public IngestScheduler ingestScheduler(List<ChainScraper> chainScrapers, Browser browser,
			IngestProperties properties, PriceRecordRepository priceRecordRepository,
			PriceStatsService priceStatsService, CategoryMap categoryMap) {
		return new IngestScheduler(chainScrapers, browser, properties, priceRecordRepository, priceStatsService,
				categoryMap, Clock.systemUTC());
	}

	/** Seeds fresh data: one background ingest run after startup when app.ingest.run-on-startup=true. */
	@Bean
	public ApplicationRunner ingestOnStartup(IngestScheduler scheduler, IngestProperties properties) {
		return args -> {
			if (!properties.isRunOnStartup()) {
				return;
			}
			Thread thread = new Thread(scheduler::runAll, "ingest-on-startup");
			thread.setDaemon(true);   // a multi-minute paced scrape must never keep the JVM alive after a stop signal
			thread.start();
		};
	}
}
