package com.stocker.gateway.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	@Value("${STOCKER_CORS_ALLOWED_ORIGINS:http://localhost:5173}")
	private List<String> allowedOrigins;

	@Bean
	SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
		return http
			.csrf(csrf -> csrf.disable())
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.authorizeExchange(exchanges -> exchanges
				// CORS preflights carry no credentials and must never require auth.
				.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.pathMatchers(HttpMethod.GET, "/healthz", "/actuator/health", "/actuator/info").permitAll()
				.pathMatchers(HttpMethod.POST,
					"/api/identity/register",
					"/api/identity/login",
					"/api/identity/refresh",
					"/api/identity/logout").permitAll()
					.pathMatchers(HttpMethod.GET, "/api/products/**").permitAll()
					.pathMatchers("/api/pantry-items/**").authenticated()
				.pathMatchers("/api/identity/**").authenticated()
				.anyExchange().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint((exchange, ex) -> writeError(exchange, HttpStatus.UNAUTHORIZED, "unauthorized"))
				.accessDeniedHandler((exchange, denied) -> writeError(exchange, HttpStatus.FORBIDDEN, "forbidden")))
			.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

	@Bean
	ReactiveJwtDecoder jwtDecoder(@Value("${stocker.jwt.secret}") String secret) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException("stocker.jwt.secret must be at least 32 bytes for HS256");
		}
		return NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
	}

	private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String message) {
		exchange.getResponse().setStatusCode(status);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"error\":\"" + message + "\"}";
		DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
		return exchange.getResponse().writeWith(Mono.just(buffer));
	}
}
