package com.stocker.identity.infrastructure.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookieFactory {

	public static final String COOKIE_NAME = "refreshToken";

	private final JwtService jwtService;
	private final boolean cookieSecure;

	public RefreshTokenCookieFactory(JwtService jwtService,
		@Value("${stocker.auth.cookie.secure:false}") boolean cookieSecure) {
		this.jwtService = jwtService;
		this.cookieSecure = cookieSecure;
	}

	public ResponseCookie create(String refreshToken) {
		return base(refreshToken).maxAge(Duration.ofSeconds(jwtService.refreshTtlSeconds())).build();
	}

	public ResponseCookie clear() {
		return base("").maxAge(0).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(COOKIE_NAME, value)
			.httpOnly(true)
			.secure(cookieSecure)
			.path("/")
			.sameSite("Lax");
	}
}
