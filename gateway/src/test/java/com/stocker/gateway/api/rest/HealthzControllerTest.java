package com.stocker.gateway.api.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(HealthzController.class)
class HealthzControllerTest {

	@Autowired
	private WebTestClient webTestClient;

	@Test
	void healthzReturnsUp() {
		webTestClient.get().uri("/healthz")
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.status").isEqualTo("UP");
	}

}
