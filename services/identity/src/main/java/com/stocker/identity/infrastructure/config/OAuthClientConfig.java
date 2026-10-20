package com.stocker.identity.infrastructure.config;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ClientSettings;
import org.springframework.util.StringUtils;

@Configuration
public class OAuthClientConfig {

	@Bean
	ClientRegistrationRepository clientRegistrationRepository(
		@Value("${stocker.oauth.github.client-id:}") String clientId,
		@Value("${stocker.oauth.github.client-secret:}") String clientSecret,
		@Value("${stocker.oauth.github.redirect-uri}") String redirectUri) {
		if (!StringUtils.hasText(clientId)) {
			return new InMemoryClientRegistrationRepository(Map.of());
		}
		ClientRegistration github = ClientRegistration.withRegistrationId("github")
			.clientId(clientId)
			.clientSecret(clientSecret)
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri(redirectUri)
			.scope("read:user", "user:email")
			.authorizationUri("https://github.com/login/oauth/authorize")
			.tokenUri("https://github.com/login/oauth/access_token")
			.userInfoUri("https://api.github.com/user")
			.userNameAttributeName("id")
			.clientName("GitHub")
			.clientSettings(ClientSettings.builder().requireProofKey(false).build())
			.build();
		return new InMemoryClientRegistrationRepository(github);
	}
}
