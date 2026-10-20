package com.stocker.gateway.infrastructure.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class InternalNetworkFilterTest {

	private final InternalNetworkFilter filter = new InternalNetworkFilter();

	private HttpStatus run(String path, String remoteIp, String forwardedFor) throws Exception {
		MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post(path)
				.remoteAddress(remoteIp == null ? null : new InetSocketAddress(InetAddress.getByName(remoteIp), 40000));
		if (forwardedFor != null) {
			builder.header("X-Forwarded-For", forwardedFor);
		}
		MockServerWebExchange exchange = MockServerWebExchange.from(builder);
		AtomicBoolean passed = new AtomicBoolean();
		filter.filter(exchange, e -> {
			passed.set(true);
			return Mono.empty();
		}).block();
		return passed.get() ? HttpStatus.OK : HttpStatus.valueOf(exchange.getResponse().getStatusCode().value());
	}

	@Test
	void loopbackAndPrivateAddressesPass() throws Exception {
		for (String ip : new String[] {"127.0.0.1", "::1", "10.1.2.3", "172.18.0.5", "192.168.1.9"}) {
			assertThat(run("/internal/shopping/updates", ip, null)).as(ip).isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void publicAddressesAreForbidden() throws Exception {
		for (String ip : new String[] {"8.8.8.8", "203.0.113.7", "172.32.0.1", "2001:db8::1"}) {
			assertThat(run("/internal/shopping/updates", ip, null)).as(ip).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	@Test
	void unknownRemoteAddressIsForbidden() throws Exception {
		assertThat(run("/internal/shopping/updates", null, null)).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aProxiedRequestIsForbiddenEvenFromAPrivateAddress() throws Exception {
		assertThat(run("/internal/shopping/updates", "172.18.0.2", "203.0.113.7")).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void otherPathsAreNotAffected() throws Exception {
		assertThat(run("/api/pricing/search", "8.8.8.8", null)).isEqualTo(HttpStatus.OK);
	}
}
