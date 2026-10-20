package com.stocker.gateway.infrastructure.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Service-to-service paths ({@code /internal/**}) answer only callers on the loopback, link-local or private
 * (RFC1918 / unique-local, i.e. Docker network) addresses; anyone else gets 403 before security or the shared-token
 * check runs. A request carrying {@code X-Forwarded-For} / {@code Forwarded} has passed through a proxy, so the
 * socket address is the proxy's and says nothing about the real caller: those are refused too. This is defence in
 * depth, not a replacement for the token or for blocking {@code /internal} at the ingress: a host on the same
 * private LAN as a published port is still a "private" caller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalNetworkFilter implements WebFilter {

	static final String PREFIX = "/internal/";

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String path = exchange.getRequest().getPath().value();
		if (!path.startsWith(PREFIX) && !path.equals("/internal")) {
			return chain.filter(exchange);
		}
		boolean proxied = exchange.getRequest().getHeaders().containsKey("X-Forwarded-For")
				|| exchange.getRequest().getHeaders().containsKey("Forwarded");
		if (!proxied && isInternal(exchange.getRequest().getRemoteAddress())) {
			return chain.filter(exchange);
		}
		exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		byte[] body = "{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8);
		return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
	}

	static boolean isInternal(InetSocketAddress remote) {
		if (remote == null) {
			return false;
		}
		InetAddress address = remote.getAddress();
		if (address == null) {
			return false;
		}
		if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
			return true;
		}
		byte[] raw = address.getAddress();
		// IPv6 unique-local fc00::/7 (not covered by isSiteLocalAddress, which is the deprecated fec0::/10).
		return raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
	}
}
