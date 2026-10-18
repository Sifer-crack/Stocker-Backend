package com.stocker.identity.infrastructure.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.stocker.identity.application.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

	private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);

	private final AuthService authService;
	private final RefreshTokenCookieFactory refreshTokenCookieFactory;
	private final String successRedirect;
	private final String failureRedirect;

	public OAuth2AuthenticationSuccessHandler(AuthService authService,
		RefreshTokenCookieFactory refreshTokenCookieFactory,
		@Value("${stocker.oauth.success-redirect}") String successRedirect,
		@Value("${stocker.oauth.failure-redirect}") String failureRedirect) {
		this.authService = authService;
		this.refreshTokenCookieFactory = refreshTokenCookieFactory;
		this.successRedirect = successRedirect;
		this.failureRedirect = failureRedirect;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
		Authentication authentication) throws IOException {
		try {
			OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
			OAuth2User principal = token.getPrincipal();
			String[] name = splitName(principal.getAttribute("name"), principal.getAttribute("login"));

			AuthService.Tokens tokens = authService.provisionOAuthUser(
				token.getAuthorizedClientRegistrationId(),
				String.valueOf((Object) principal.getAttribute("id")),
				principal.getAttribute("email"),
				name[0],
				name[1],
				principal.getAttribute("avatar_url"));
			response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.create(tokens.refreshToken()).toString());
			response.sendRedirect(successRedirect);
		} catch (RuntimeException ex) {
			log.warn("OAuth login provisioning failed", ex);
			response.sendRedirect(failureRedirect + "?error=oauth_login_failed");
		}
	}

	private String[] splitName(String name, String login) {
		if (name == null || name.isBlank()) {
			return new String[] { login, null };
		}
		String trimmed = name.trim();
		int space = trimmed.indexOf(' ');
		if (space < 0) {
			return new String[] { trimmed, null };
		}
		return new String[] { trimmed.substring(0, space), trimmed.substring(space + 1).trim() };
	}
}
