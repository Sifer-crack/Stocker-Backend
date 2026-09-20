package com.stocker.gateway.infrastructure.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * A CorsWebFilter (not spring.cloud.gateway.*.globalcors) so this applies to every WebFlux
 * request this app handles - including locally-implemented BFF controllers like
 * PricingController, which don't go through Spring Cloud Gateway's route/filter chain at all.
 */
@Configuration
public class CorsConfig {

	@Bean
	public CorsWebFilter corsWebFilter(
			@Value("#{'${app.cors.allowed-origins}'.split(',')}") List<String> allowedOrigins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return new CorsWebFilter(source);
	}
}
