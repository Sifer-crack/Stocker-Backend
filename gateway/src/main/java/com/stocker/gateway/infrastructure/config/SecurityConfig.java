package com.stocker.gateway.infrastructure.config;

import java.nio.charset.StandardCharsets;

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
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	@Bean
	SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder) {
		return http
			.csrf(csrf -> csrf.disable())
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.authorizeExchange(exchanges -> exchanges
				.pathMatchers(HttpMethod.GET, "/healthz", "/actuator/health", "/actuator/info").permitAll()
				.pathMatchers("/api/identity/**").authenticated()
				.anyExchange().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtDecoder(jwtDecoder)))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint((exchange, ex) -> writeError(exchange, HttpStatus.UNAUTHORIZED, "unauthorized"))
				.accessDeniedHandler((exchange, denied) -> writeError(exchange, HttpStatus.FORBIDDEN, "forbidden")))
			.build();
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
