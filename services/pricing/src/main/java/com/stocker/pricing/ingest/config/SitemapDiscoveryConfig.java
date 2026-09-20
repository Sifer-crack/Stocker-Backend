package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.sitemap.SitemapCategoryCacheRepository;
import com.stocker.pricing.ingest.sitemap.SitemapCategoryDiscoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConditionalOnProperty(name = "app.ingest.enabled", havingValue = "true")
public class SitemapDiscoveryConfig {

	@Bean
	public SitemapCategoryDiscoveryService sitemapCategoryDiscoveryService(WebClient webClient,
			SitemapCategoryCacheRepository repository, IngestProperties properties) {
		return new SitemapCategoryDiscoveryService(webClient, repository, properties);
	}
}
