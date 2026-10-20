package com.stocker.identity.application;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.stocker.identity.domain.User;
import com.stocker.identity.infrastructure.persistence.UserRepository;
import com.stocker.identity.infrastructure.security.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceOAuthTest {

	@Mock
	private UserRepository userRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private AuthenticationManager authenticationManager;

	@Mock
	private JwtService jwtService;

	@InjectMocks
	private AuthService authService;

	private void stubTokens() {
		when(jwtService.issueAccessToken(any(), any())).thenReturn("access");
		when(jwtService.issueRefreshToken(any(), any())).thenReturn("refresh");
		when(jwtService.accessTtlSeconds()).thenReturn(900L);
	}

	@Test
	void createsUserWhenNeitherProviderNorEmailMatch() {
		stubTokens();
		when(userRepository.findByOauthProviderAndOauthProviderId("github", "42")).thenReturn(Optional.empty());
		when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.empty());
		when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			user.setUserId(UUID.randomUUID());
			return user;
		});

		AuthService.Tokens tokens = authService.provisionOAuthUser(
			"github", "42", "jane@example.com", "Jane", "Doe", "https://avatar");

		assertThat(tokens.body().accessToken()).isEqualTo("access");
		assertThat(tokens.refreshToken()).isEqualTo("refresh");
		verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(user ->
			"github".equals(user.getOauthProvider())
				&& "42".equals(user.getOauthProviderId())
				&& "jane@example.com".equals(user.getEmail())
				&& user.getPasswordHash() == null));
	}

	@Test
	void linksExistingPasswordAccountByEmail() {
		stubTokens();
		User existing = new User();
		existing.setUserId(UUID.randomUUID());
		existing.setEmail("jane@example.com");
		existing.setPasswordHash("bcrypt");
		when(userRepository.findByOauthProviderAndOauthProviderId("github", "42")).thenReturn(Optional.empty());
		when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(existing));
		when(userRepository.save(existing)).thenReturn(existing);

		authService.provisionOAuthUser("github", "42", "jane@example.com", "Jane", "Doe", "https://avatar");

		assertThat(existing.getOauthProvider()).isEqualTo("github");
		assertThat(existing.getOauthProviderId()).isEqualTo("42");
		assertThat(existing.getAvatarUrl()).isEqualTo("https://avatar");
		verify(userRepository).save(existing);
	}

	@Test
	void returnsExistingProviderUserWithoutSaving() {
		stubTokens();
		User existing = new User();
		existing.setUserId(UUID.randomUUID());
		existing.setEmail("jane@example.com");
		existing.setOauthProvider("github");
		existing.setOauthProviderId("42");
		when(userRepository.findByOauthProviderAndOauthProviderId("github", "42")).thenReturn(Optional.of(existing));

		authService.provisionOAuthUser("github", "42", "jane@example.com", "Jane", "Doe", "https://avatar");

		org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).save(any());
	}
}
