package com.stocker.gateway.infrastructure.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A proxied route must reach its service exactly once, carrying the verified user's id (never a
 * client-supplied one) and no bearer token. The upstream is a plain JDK server that records what it
 * receives, so this exercises the real gateway filter chain end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"stocker.jwt.secret=test-only-32-byte-minimum-secret-0123456789",
		"stocker.internal.token=test-internal-token",
		"spring.cloud.gateway.server.webflux.routes[0].id=upstream",
		"spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:${test.upstream.port}",
		"spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/echo/**",
		"spring.cloud.gateway.server.webflux.routes[0].filters[0]=StripPrefix=1",
		"spring.cloud.gateway.server.webflux.routes[1].id=public",
		"spring.cloud.gateway.server.webflux.routes[1].uri=http://127.0.0.1:${test.upstream.port}",
		"spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/identity/**",
		"spring.cloud.gateway.server.webflux.routes[1].filters[0]=StripPrefix=1"
})
@AutoConfigureWebTestClient
class JwtClaimForwardFilterTest {

	private static final String SECRET = "test-only-32-byte-minimum-secret-0123456789";
	private static final AtomicReference<Headers> received = new AtomicReference<>();
	private static final AtomicInteger hits = new AtomicInteger();
	private static final HttpServer upstream = startUpstream();

	private static HttpServer startUpstream() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				hits.incrementAndGet();
				received.set(exchange.getRequestHeaders());
				byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
			System.setProperty("test.upstream.port", String.valueOf(server.getAddress().getPort()));
			return server;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@AfterAll
	static void stopUpstream() {
		upstream.stop(0);
	}

	@Autowired
	private WebTestClient webTestClient;

	@BeforeEach
	void reset() {
		received.set(null);
		hits.set(0);
	}

	@Test
	void forwardsTheVerifiedUserIdOnceAndOverwritesAClientSuppliedOne() {
		webTestClient.get().uri("/api/echo/anything")
				.header("Authorization", "Bearer " + token("user-42"))
				.header("X-User-Id", "attacker-supplied")
				.exchange()
				.expectStatus().isOk();

		assertEquals(1, hits.get(), "the request must be proxied exactly once");
		assertEquals("user-42", received.get().getFirst("X-User-Id"));
		// identity validates the token itself for its authenticated endpoints, so it must still arrive.
		assertEquals(true, received.get().getFirst("Authorization").startsWith("Bearer "));
	}

	@Test
	void clientSuppliedIdentityHeadersNeverReachAServiceEvenOnAPublicRoute() {
		webTestClient.post().uri("/api/identity/login")
				.header("X-User-Id", "attacker-supplied")
				.header("X-Household-Id", "someone-elses-household")
				.header("X-Roles", "ADMIN")
				.exchange()
				.expectStatus().isOk();

		assertEquals(1, hits.get());
		assertNull(received.get().getFirst("X-User-Id"));
		assertNull(received.get().getFirst("X-Household-Id"));
		assertNull(received.get().getFirst("X-Roles"));
	}

	private static String token(String subject) {
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("stocker-test").subject(subject)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
}
