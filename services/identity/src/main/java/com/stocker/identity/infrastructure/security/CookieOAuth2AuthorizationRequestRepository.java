package com.stocker.identity.infrastructure.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CookieOAuth2AuthorizationRequestRepository
	implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	private static final Logger log = LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository.class);
	private static final String COOKIE_NAME = "oauth2_auth_request";
	private static final Duration COOKIE_MAX_AGE = Duration.ofMinutes(5);
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec signingKey;
	private final boolean cookieSecure;

	public CookieOAuth2AuthorizationRequestRepository(
		@Value("${stocker.jwt.secret}") String secret,
		@Value("${stocker.auth.cookie.secure:false}") boolean cookieSecure) {
		this.signingKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		this.cookieSecure = cookieSecure;
	}

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		Cookie cookie = findCookie(request);
		return cookie == null ? null : deserialize(cookie.getValue());
	}

	@Override
	public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
		HttpServletRequest request, HttpServletResponse response) {
		if (authorizationRequest == null) {
			clearCookie(response);
			return;
		}
		response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, serialize(authorizationRequest))
			.httpOnly(true)
			.secure(cookieSecure)
			.path("/")
			.maxAge(COOKIE_MAX_AGE)
			.sameSite("Lax")
			.build()
			.toString());
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
		HttpServletResponse response) {
		OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
		clearCookie(response);
		return authorizationRequest;
	}

	private void clearCookie(HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE_NAME, "")
			.httpOnly(true)
			.secure(cookieSecure)
			.path("/")
			.maxAge(0)
			.sameSite("Lax")
			.build()
			.toString());
	}

	private Cookie findCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		return Arrays.stream(cookies)
			.filter(c -> COOKIE_NAME.equals(c.getName()))
			.findFirst()
			.orElse(null);
	}

	private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
				out.writeObject(authorizationRequest);
			}
			byte[] payload = bytes.toByteArray();
			return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(sign(payload));
		} catch (IOException ex) {
			throw new IllegalStateException("Unable to serialize OAuth2 authorization request", ex);
		}
	}

	private OAuth2AuthorizationRequest deserialize(String value) {
		int separator = value.lastIndexOf('.');
		if (separator < 0) {
			return null;
		}
		try {
			byte[] payload = DECODER.decode(value.substring(0, separator));
			byte[] signature = DECODER.decode(value.substring(separator + 1));
			if (!MessageDigest.isEqual(sign(payload), signature)) {
				return null;
			}
			try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(payload))) {
				return (OAuth2AuthorizationRequest) in.readObject();
			}
		} catch (IOException | ClassNotFoundException | IllegalArgumentException ex) {
			log.debug("Discarding unreadable OAuth2 authorization request cookie", ex);
			return null;
		}
	}

	private byte[] sign(byte[] payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(signingKey);
			return mac.doFinal(payload);
		} catch (Exception ex) {
			throw new IllegalStateException("Unable to sign OAuth2 authorization request", ex);
		}
	}
}
