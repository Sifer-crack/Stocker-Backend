package com.stocker.identity.infrastructure.security;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.application.AuthService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

	private static final String SUCCESS = "http://localhost:5173/oauth/callback";
	private static final String FAILURE = "http://localhost:5173/oauth/callback";

	@Mock
	private AuthService authService;

	@Mock
	private RefreshTokenCookieFactory refreshTokenCookieFactory;

	private OAuth2AuthenticationSuccessHandler newHandler() {
		return new OAuth2AuthenticationSuccessHandler(authService, refreshTokenCookieFactory, SUCCESS, FAILURE);
	}

	private OAuth2AuthenticationToken githubToken() {
		DefaultOAuth2User user = new DefaultOAuth2User(
			List.of(new SimpleGrantedAuthority("ROLE_USER")),
			Map.of("id", 42, "email", "jane@example.com", "name", "Jane Doe",
				"login", "janedoe", "avatar_url", "https://avatar"),
			"id");
		return new OAuth2AuthenticationToken(user, user.getAuthorities(), "github");
	}

	@Test
	void setsRefreshCookieAndRedirectsOnSuccess() throws Exception {
		when(refreshTokenCookieFactory.create("refresh")).thenReturn(ResponseCookie.from("refreshToken", "refresh").build());
		when(authService.provisionOAuthUser(eq("github"), eq("42"), eq("jane@example.com"), eq("Jane"), eq("Doe"),
			eq("https://avatar")))
			.thenReturn(new AuthService.Tokens(new AuthResponse("access", "Bearer", 900), "refresh"));

		MockHttpServletResponse response = new MockHttpServletResponse();
		newHandler().onAuthenticationSuccess(new MockHttpServletRequest(), response, githubToken());

		assertThat(response.getRedirectedUrl()).isEqualTo(SUCCESS);
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("refreshToken=refresh");
	}

	@Test
	void redirectsToFailureWhenProvisioningFails() throws Exception {
		when(authService.provisionOAuthUser(any(), any(), any(), any(), any(), any()))
			.thenThrow(new AuthService.BadCredentialsException());

		MockHttpServletResponse response = new MockHttpServletResponse();
		newHandler().onAuthenticationSuccess(new MockHttpServletRequest(), response, githubToken());

		assertThat(response.getRedirectedUrl()).isEqualTo(FAILURE + "?error=oauth_login_failed");
	}
}
