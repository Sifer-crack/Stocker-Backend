package com.stocker.shopping.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GatewayClientConfig {

	/** Short timeouts: a push is best effort, so an unreachable gateway must fail fast. */
	@Bean
	public RestClient gatewayRestClient(@Value("${app.gateway.base-url}") String baseUrl) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
		factory.setReadTimeout(Duration.ofSeconds(3));
		return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}
}
