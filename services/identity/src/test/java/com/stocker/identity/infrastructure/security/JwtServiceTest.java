package com.stocker.identity.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

	private final JwtService jwtService = new JwtService("test-only-32-byte-minimum-secret-0123456789", 60, 3600);

	@Test
	void refreshTokenRoundTrip() {
		String refresh = jwtService.issueRefreshToken("user-1", "jdoe@example.com");
		JwtService.ValidatedRefresh validated = jwtService.validateRefreshToken(refresh);
		assertEquals("user-1", validated.userId());
		assertEquals("jdoe@example.com", validated.email());
	}

	@Test
	void accessTokenRejectedAsRefresh() {
		String access = jwtService.issueAccessToken("user-1", "jdoe@example.com");
		assertThrows(JwtService.InvalidRefreshTokenException.class,
			() -> jwtService.validateRefreshToken(access));
	}

	@Test
	void garbageRejectedAsRefresh() {
		assertThrows(JwtService.InvalidRefreshTokenException.class,
			() -> jwtService.validateRefreshToken("bogus.token.here"));
	}

	@Test
	void shortSecretFailsFast() {
		assertThrows(IllegalStateException.class, () -> new JwtService("short", 60, 3600));
	}
}
