package com.stocker.gateway.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"app.cors.allowed-origins=http://app.example:5173,http://other.example"
})
@AutoConfigureWebTestClient
class CorsTest {

	@Autowired
	private WebTestClient webTestClient;

	private WebTestClient.ResponseSpec preflight(String origin, String headers) {
		return webTestClient.options().uri("/api/pricing/search")
				.header("Origin", origin)
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", headers)
				.exchange();
	}

	@Test
	void allowedOriginsGetOneSetOfCorsHeaders() {
		for (String origin : new String[] {"http://app.example:5173", "http://other.example"}) {
			preflight(origin, "authorization,content-type").expectStatus().isOk()
					.expectHeader().valueEquals("Access-Control-Allow-Origin", origin)
					.expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
		}
	}

	@Test
	void unknownOriginIsBlocked() {
		preflight("http://evil.example", "authorization").expectStatus().isForbidden();
	}

	@Test
	void unlistedRequestHeaderIsBlocked() {
		preflight("http://app.example:5173", "x-user-id").expectStatus().isForbidden();
	}

	@Test
	void simpleRequestFromAnAllowedOriginCarriesTheHeaderExactlyOnce() {
		var headers = webTestClient.get().uri("/healthz").header("Origin", "http://app.example:5173").exchange()
				.expectStatus().isOk()
				.returnResult(String.class).getResponseHeaders();
		assertThat(headers.get("Access-Control-Allow-Origin")).containsExactly("http://app.example:5173");
	}
}
