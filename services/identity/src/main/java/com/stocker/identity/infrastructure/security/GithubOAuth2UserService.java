package com.stocker.identity.infrastructure.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GithubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

	private static final String PROVIDER_EMAIL = "email";

	private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
	private final RestClient restClient;

	public GithubOAuth2UserService(RestClient.Builder restClientBuilder) {
		this.restClient = restClientBuilder.baseUrl("https://api.github.com").build();
	}

	@Override
	public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
		OAuth2User user = delegate.loadUser(userRequest);
		Map<String, Object> attributes = new LinkedHashMap<>(user.getAttributes());

		String email = stringAttribute(attributes, PROVIDER_EMAIL);
		if (email == null || email.isBlank()) {
			email = fetchPrimaryEmail(userRequest.getAccessToken().getTokenValue());
			if (email != null) {
				attributes.put(PROVIDER_EMAIL, email);
			}
		}
		if (email == null || email.isBlank()) {
			throw new OAuth2AuthenticationException(
				new OAuth2Error("email_unavailable"), "GitHub account has no accessible verified email");
		}

		return new DefaultOAuth2User(user.getAuthorities(), attributes, "id");
	}

	private String stringAttribute(Map<String, Object> attributes, String key) {
		Object value = attributes.get(key);
		return value == null ? null : value.toString();
	}

	private String fetchPrimaryEmail(String accessToken) {
		List<Map<String, Object>> emails = restClient.get()
			.uri("/user/emails")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(HttpHeaders.ACCEPT, "application/vnd.github+json")
			.header(HttpHeaders.USER_AGENT, "stocker-identity")
			.retrieve()
			.body(new ParameterizedTypeReference<>() {
			});
		if (emails == null) {
			return null;
		}
		return emails.stream()
			.filter(e -> Boolean.TRUE.equals(e.get("verified")))
			.filter(e -> Boolean.TRUE.equals(e.get("primary")))
			.map(e -> (String) e.get("email"))
			.findFirst()
			.orElseGet(() -> emails.stream()
				.filter(e -> Boolean.TRUE.equals(e.get("verified")))
				.map(e -> (String) e.get("email"))
				.findFirst()
				.orElse(null));
	}
}
