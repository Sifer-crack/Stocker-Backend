package com.stocker.gateway.infrastructure.config;

import java.util.Collection;
import java.util.stream.Collectors;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Component
public class JwtClaimForwardFilter implements GlobalFilter, Ordered {

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (!path.startsWith("/api/")) {
			return chain.filter(exchange);
		}
		return ReactiveSecurityContextHolder.getContext()
			.map(ctx -> ctx.getAuthentication())
			.filter(JwtAuthenticationToken.class::isInstance)
			.cast(JwtAuthenticationToken.class)
			.flatMap(auth -> {
				String userId = auth.getToken().getSubject();
				String households = extractClaimAsString(auth, "householdIds", "householdId");
				String roles = auth.getAuthorities().stream()
					.map(a -> a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority())
					.collect(Collectors.joining(","));

				ServerWebExchange mutated = exchange.mutate()
					.request(builder -> builder
						.headers(headers -> {
							headers.remove("Authorization");
							if (userId != null) {
								headers.set("X-User-Id", userId);
							}
							if (!households.isEmpty()) {
								headers.set("X-Household-Id", households);
							}
							if (!roles.isEmpty()) {
								headers.set("X-Roles", roles);
							}
						}))
					.build();
				return chain.filter(mutated);
			})
			.switchIfEmpty(chain.filter(exchange));
	}

	private String extractClaimAsString(JwtAuthenticationToken auth, String... names) {
		for (String name : names) {
			Object claim = auth.getToken().getClaims().get(name);
			if (claim instanceof Collection<?> values) {
				String joined = values.stream().map(Object::toString).collect(Collectors.joining(","));
				if (!joined.isEmpty()) {
					return joined;
				}
			} else if (claim != null) {
				return claim.toString();
			}
		}
		return "";
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1000;
	}
}
