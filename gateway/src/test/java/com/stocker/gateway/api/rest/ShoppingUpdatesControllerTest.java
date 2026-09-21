package com.stocker.gateway.api.rest;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"stocker.jwt.secret=test-only-32-byte-minimum-secret-0123456789",
		"stocker.internal.token=test-internal-token"
})
@AutoConfigureWebTestClient
class ShoppingUpdatesControllerTest {

	private static final String SECRET = "test-only-32-byte-minimum-secret-0123456789";

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void theInternalCallbackRejectsAMissingOrWrongToken() {
		post(null, update("user-1", "a")).expectStatus().isUnauthorized();
		post("wrong-token", update("user-1", "a")).expectStatus().isUnauthorized();
	}

	@Test
	void theInternalCallbackNeedsNoUserJwtButRequiresAnOwnerAndAnItem() {
		post("test-internal-token", "{\"source\":\"sync\",\"item\":{\"id\":\"a\"}}").expectStatus().isBadRequest();
		post("test-internal-token", "{\"userId\":\"user-1\",\"source\":\"sync\"}").expectStatus().isBadRequest();
		post("test-internal-token", update("user-1", "a")).expectStatus().isAccepted();
	}

	@Test
	void theStreamRequiresAValidUserJwt() {
		webTestClient.get().uri("/api/updates/shopping").accept(MediaType.TEXT_EVENT_STREAM)
				.exchange().expectStatus().isUnauthorized();
	}

	@Test
	void anUpdateReachesOnlyItsOwnersStreamCarryingTheFullComparison() {
		Flux<ServerSentEvent<String>> events = webTestClient.mutate().responseTimeout(Duration.ofSeconds(20)).build()
				.get().uri("/api/updates/shopping")
				.header("Authorization", "Bearer " + token("user-1"))
				.accept(MediaType.TEXT_EVENT_STREAM)
				.exchange()
				.expectStatus().isOk()
				.returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
				.getResponseBody()
				.filter(event -> event.event() != null);

		StepVerifier.create(events)
				.then(() -> {
					post("test-internal-token", update("user-2", "someone-elses")).expectStatus().isAccepted();
					post("test-internal-token", update("user-1", "mine")).expectStatus().isAccepted();
				})
				.assertNext(event -> {
					assertEquals("shopping-item-updated", event.event());
					String data = event.data();
					assertTrue(data.contains("\"id\":\"mine\""), data);
					assertTrue(data.contains("\"source\":\"backfill\""), data);
					assertTrue(data.contains("\"comparisonStatus\":\"available\""), data);
					assertTrue(data.contains("\"chainId\":\"paknsave\""), data);
					assertTrue(data.contains("\"prices\":["), data);
					assertFalse(data.contains("userId"), "the owner id is routing data, not for the browser: " + data);
				})
				.thenCancel()
				.verify(Duration.ofSeconds(20));
	}

	private WebTestClient.ResponseSpec post(String token, String body) {
		WebTestClient.RequestBodySpec request = webTestClient.post().uri("/internal/shopping/updates")
				.contentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			request = (WebTestClient.RequestBodySpec) request.header("X-Internal-Token", token);
		}
		return request.bodyValue(body).exchange();
	}

	private static String update(String userId, String itemId) {
		return "{\"userId\":\"" + userId + "\",\"source\":\"backfill\",\"item\":{\"id\":\"" + itemId
				+ "\",\"name\":\"milk\",\"quantity\":1,\"comparisonStatus\":\"available\","
				+ "\"cheapest\":{\"chainId\":\"paknsave\",\"storeId\":\"ps:1\",\"priceAmount\":4.20,\"currency\":\"NZD\"},"
				+ "\"prices\":[{\"chainId\":\"paknsave\",\"storeId\":\"ps:1\",\"priceAmount\":4.20,\"currency\":\"NZD\"},"
				+ "{\"chainId\":\"newworld\",\"storeId\":\"nw:1\",\"priceAmount\":4.80,\"currency\":\"NZD\"}]}}";
	}

	private static String token(String subject) {
		NimbusJwtEncoder encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("stocker-test").subject(subject)
				.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}
}
