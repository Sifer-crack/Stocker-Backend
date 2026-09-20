package com.stocker.gateway.api.rest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"stocker.jwt.secret=test-only-32-byte-minimum-secret-0123456789",
		"spring.cloud.gateway.server.webflux.routes[0].id=identity",
		"spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:9",
		"spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/identity/**",
		"spring.cloud.gateway.server.webflux.routes[0].filters[0]=StripPrefix=1"
})
@AutoConfigureWebTestClient
class GatewaySecurityTest {

	@Autowired
	private WebTestClient webTestClient;

	@Value("${stocker.jwt.secret}")
	private String secret;

	@Test
	void healthzIsPublic() {
		webTestClient.get().uri("/healthz")
			.exchange()
			.expectStatus().isOk();
	}

	@Test
	void identityRouteRequiresAuth() {
		webTestClient.get().uri("/api/identity/healthz")
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void identityRouteWithInvalidTokenIsUnauthorized() {
		webTestClient.get().uri("/api/identity/healthz")
			.header("Authorization", "Bearer invalid.token.here")
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void identityRouteWithValidJwtPassesSecurity() {
		String token = realHs256Token("user-123");
		webTestClient.get().uri("/api/identity/healthz")
			.header("Authorization", "Bearer " + token)
			.exchange()
			.expectStatus().value(status -> {
				if (status == 401 || status == 403) {
					throw new AssertionError("expected to pass gateway security, got " + status);
				}
			});
	}

	private String realHs256Token(String subject) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(
			new ImmutableSecret<>(new SecretKeySpec(keyBytes, "HmacSHA256")));
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("stocker-test")
			.subject(subject)
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300))
			.build();
		return encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(),
			claims)).getTokenValue();
	}
}
