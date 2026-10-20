package com.stocker.gateway.infrastructure.config;

import java.util.Collection;
import java.util.stream.Collectors;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Tells downstream services who the caller is, from the JWT the gateway has already verified.
 *
 * <p>Runs for every proxied request. It always removes any client-supplied {@code X-User-Id},
 * {@code X-Household-Id} and {@code X-Roles} first - services trust these headers, so a caller must
 * never be able to set them - then, if the request carries a verified JWT, sets them from its claims.
 * Public routes (login, register) get the removal too. The {@code Authorization} header is left in
 * place: identity validates the token itself for its authenticated endpoints.
 *
 * <p>Note this is a route-level filter chain: by the time it runs, route filters such as
 * {@code StripPrefix} have already rewritten the path, so it must not depend on the path.
 */
@Component
public class JwtClaimForwardFilter implements GlobalFilter, Ordered {

	static final String USER_HEADER = "X-User-Id";
	static final String HOUSEHOLD_HEADER = "X-Household-Id";
	static final String ROLES_HEADER = "X-Roles";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		ServerWebExchange sanitized = exchange.mutate()
			.request(builder -> builder.headers(headers -> {
				headers.remove(USER_HEADER);
				headers.remove(HOUSEHOLD_HEADER);
				headers.remove(ROLES_HEADER);
			}))
			.build();
		return ReactiveSecurityContextHolder.getContext()
			.map(SecurityContext::getAuthentication)
			.filter(JwtAuthenticationToken.class::isInstance)
			.cast(JwtAuthenticationToken.class)
			.map(auth -> withIdentity(sanitized, auth))
			.defaultIfEmpty(sanitized)
			.flatMap(chain::filter);
	}

	private ServerWebExchange withIdentity(ServerWebExchange exchange, JwtAuthenticationToken auth) {
		String userId = auth.getToken().getSubject();
		String households = extractClaimAsString(auth, "householdIds", "householdId");
		String roles = auth.getAuthorities().stream()
			.map(a -> a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority())
			.collect(Collectors.joining(","));
		return exchange.mutate()
			.request(builder -> builder.headers(headers -> {
				if (userId != null) {
					headers.set(USER_HEADER, userId);
				}
				if (!households.isEmpty()) {
					headers.set(HOUSEHOLD_HEADER, households);
				}
				if (!roles.isEmpty()) {
					headers.set(ROLES_HEADER, roles);
				}
			}))
			.build();
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
