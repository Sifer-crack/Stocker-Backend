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

	@Test
	void accessTokenIsAccepted() {
		webTestClient.get().uri("/api/identity/healthz")
			.header("Authorization", "Bearer " + token("user-1", "access"))
			.exchange()
			.expectStatus().value(status -> {
				if (status == 401 || status == 403) {
					throw new AssertionError("expected an access token to pass, got " + status);
				}
			});
	}

	@Test
	void refreshTokenIsRejectedAsBearer() {
		webTestClient.get().uri("/api/identity/healthz")
			.header("Authorization", "Bearer " + token("user-1", "refresh"))
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void tokenWithoutATypeClaimIsRejected() {
		webTestClient.get().uri("/api/identity/healthz")
			.header("Authorization", "Bearer " + token("user-1", null))
			.exchange()
			.expectStatus().isUnauthorized();
	}

	@Test
	void catalogMutationsAreNotReachableThroughTheGateway() {
		String access = token("user-1", "access");
		webTestClient.delete().uri("/api/products/5ddcf122-267b-426e-bae5-82daeff1161e")
			.header("Authorization", "Bearer " + access).exchange().expectStatus().isForbidden();
		webTestClient.post().uri("/api/products/grouping/import")
			.header("Authorization", "Bearer " + access).exchange().expectStatus().isForbidden();
		webTestClient.post().uri("/api/products?name=X&unit=1")
			.header("Authorization", "Bearer " + access).exchange().expectStatus().isForbidden();
		webTestClient.delete().uri("/api/products/5ddcf122-267b-426e-bae5-82daeff1161e")
			.exchange().expectStatus().isUnauthorized();
	}

	private String realHs256Token(String subject) {
		return token(subject, "access");
	}

	private String token(String subject, String type) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(
			new ImmutableSecret<>(new SecretKeySpec(keyBytes, "HmacSHA256")));
		JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
			.issuer("stocker-test")
			.subject(subject)
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300));
		if (type != null) {
			builder.claim("type", type);
		}
		JwtClaimsSet claims = builder.build();
		return encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(),
			claims)).getTokenValue();
	}
}
