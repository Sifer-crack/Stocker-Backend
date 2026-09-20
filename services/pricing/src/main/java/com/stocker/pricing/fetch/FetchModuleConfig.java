package com.stocker.pricing.fetch;

import org.springframework.context.annotation.Import;
import com.stocker.pricing.fetch.config.WebClientConfig;
import com.stocker.pricing.fetch.config.WebFetcherConfig;
import com.stocker.pricing.fetch.config.WebFetcherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WebFetcherProperties.class)
@Import({WebClientConfig.class, WebFetcherConfig.class})
public class FetchModuleConfig {
}
