package com.stocker.identity.infrastructure.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

	private final String failureRedirect;

	public OAuth2AuthenticationFailureHandler(@Value("${stocker.oauth.failure-redirect}") String failureRedirect) {
		this.failureRedirect = failureRedirect;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException exception) throws IOException {
		String errorCode = exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauthEx
			? oauthEx.getError().getErrorCode()
			: exception.getClass().getSimpleName();
		String reason = URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
		response.sendRedirect(failureRedirect + "?error=" + reason);
	}
}
