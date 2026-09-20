package com.stocker.pricing.ingest.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IngestProperties.class)
@Import({PlaywrightConfig.class, ChainScraperBeansConfig.class, IngestSchedulerConfig.class,
		SitemapDiscoveryConfig.class})
public class IngestModuleConfig {
}
