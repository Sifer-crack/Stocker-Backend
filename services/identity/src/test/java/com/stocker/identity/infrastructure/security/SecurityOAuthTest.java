package com.stocker.identity.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stocker.identity.api.rest.HealthzController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthzController.class)
@Import({
	SecurityConfig.class,
	org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration.class,
	SecurityOAuthTest.GithubRegistrationConfig.class })
class SecurityOAuthTest {

	@TestConfiguration
	static class GithubRegistrationConfig {
		@Bean
		ClientRegistrationRepository clientRegistrationRepository() {
			ClientRegistration github = ClientRegistration.withRegistrationId("github")
				.clientId("test-client")
				.clientSecret("test-secret")
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("http://localhost:8080/api/identity/login/oauth2/code/github")
				.scope("read:user", "user:email")
				.authorizationUri("https://github.com/login/oauth/authorize")
				.tokenUri("https://github.com/login/oauth/access_token")
				.userInfoUri("https://api.github.com/user")
				.userNameAttributeName("id")
				.clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(false).build())
				.build();
			return new InMemoryClientRegistrationRepository(github);
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private com.stocker.identity.infrastructure.security.GithubOAuth2UserService githubOAuth2UserService;

	@MockitoBean
	private com.stocker.identity.infrastructure.security.CookieOAuth2AuthorizationRequestRepository cookieOAuth2AuthorizationRequestRepository;

	@MockitoBean
	private com.stocker.identity.infrastructure.security.OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;

	@MockitoBean
	private com.stocker.identity.infrastructure.security.OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;

	@MockitoBean
	private com.stocker.identity.infrastructure.security.JwtService jwtService;

	@MockitoBean
	private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

	@MockitoBean
	private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

	@Test
	void githubAuthorizationEndpointRedirectsToGithub() throws Exception {
		mockMvc.perform(get("/identity/oauth2/authorization/github"))
			.andExpect(status().is3xxRedirection())
			.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://github.com/login/oauth/authorize")))
			.andExpect(header().string("Location", org.hamcrest.Matchers.containsString("client_id=test-client")));
	}
}
