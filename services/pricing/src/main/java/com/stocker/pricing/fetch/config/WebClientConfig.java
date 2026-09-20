package com.stocker.pricing.fetch.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

	@Bean
	public WebClient webClient(WebFetcherProperties properties) {
		HttpClient httpClient = HttpClient.create()
				.responseTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
		return WebClient.builder()
				.clientConnector(new ReactorClientHttpConnector(httpClient))
				.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
				.build();
	}
}