package com.stocker.pricing.service.cache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PricingCacheProperties.class)
public class PricingCacheModuleConfig {

	/** Bounds the synchronous on-demand cache-miss fallback so it can never block a gRPC caller indefinitely. */
	@Bean(destroyMethod = "shutdown")
	public ExecutorService priceFallbackExecutor() {
		return Executors.newFixedThreadPool(8);
	}
}
