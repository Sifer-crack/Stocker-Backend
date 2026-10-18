package com.stocker.identity.application;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.api.rest.dto.LoginRequest;
import com.stocker.identity.api.rest.dto.RegisterRequest;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.domain.User;
import com.stocker.identity.infrastructure.persistence.UserRepository;
import com.stocker.identity.infrastructure.security.JwtService;

@Service
public class AuthService {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final JwtService jwtService;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
		AuthenticationManager authenticationManager, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
	}

	public UserResponse register(RegisterRequest request) {
		if (userRepository.findByEmail(request.email()).isPresent()) {
			throw new EmailAlreadyExistsException();
		}
		User user = new User();
		user.setEmail(request.email());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setFirstName(request.firstName());
		user.setLastName(request.lastName());
		user.setGroceryBudget(request.groceryBudget());
		User saved = userRepository.save(user);
		return toResponse(saved);
	}

	public Tokens login(LoginRequest request) {
		try {
			authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password()));
		} catch (org.springframework.security.core.AuthenticationException ex) {
			throw new BadCredentialsException(ex);
		}
		User user = userRepository.findByEmail(request.email()).orElseThrow(BadCredentialsException::new);
		return tokens(user);
	}

	@Transactional
	public Tokens provisionOAuthUser(String provider, String providerId, String email, String firstName,
		String lastName, String avatarUrl) {
		if (provider == null || providerId == null || email == null || email.isBlank()) {
			throw new BadCredentialsException();
		}
		User user = userRepository.findByOauthProviderAndOauthProviderId(provider, providerId)
			.orElseGet(() -> userRepository.findByEmail(email)
				.map(existing -> linkOAuth(existing, provider, providerId, avatarUrl))
				.orElseGet(() -> createOAuthUser(provider, providerId, email, firstName, lastName, avatarUrl)));
		return tokens(user);
	}

	private User linkOAuth(User user, String provider, String providerId, String avatarUrl) {
		user.setOauthProvider(provider);
		user.setOauthProviderId(providerId);
		if (avatarUrl != null) {
			user.setAvatarUrl(avatarUrl);
		}
		return userRepository.save(user);
	}

	private User createOAuthUser(String provider, String providerId, String email, String firstName,
		String lastName, String avatarUrl) {
		User user = new User();
		user.setEmail(email);
		user.setOauthProvider(provider);
		user.setOauthProviderId(providerId);
		user.setFirstName(firstName);
		user.setLastName(lastName);
		user.setAvatarUrl(avatarUrl);
		return userRepository.save(user);
	}

	public Tokens refresh(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BadCredentialsException();
		}
		JwtService.ValidatedRefresh validated;
		try {
			validated = jwtService.validateRefreshToken(refreshToken);
		} catch (JwtService.InvalidRefreshTokenException ex) {
			throw new BadCredentialsException(ex);
		}
		User user;
		try {
			user = userRepository.findById(java.util.UUID.fromString(validated.userId()))
				.orElseThrow(BadCredentialsException::new);
		} catch (IllegalArgumentException ex) {
			throw new BadCredentialsException(ex);
		}
		return tokens(user);
	}

	private Tokens tokens(User user) {
		String userId = user.getUserId().toString();
		AuthResponse body = new AuthResponse(
			jwtService.issueAccessToken(userId, user.getEmail()),
			"Bearer",
			jwtService.accessTtlSeconds());
		String refreshToken = jwtService.issueRefreshToken(userId, user.getEmail());
		return new Tokens(body, refreshToken);
	}

	private UserResponse toResponse(User user) {
		return new UserResponse(
			user.getUserId(),
			user.getEmail(),
			user.getFirstName(),
			user.getLastName(),
			user.getGroceryBudget());
	}

	public UserResponse getCurrentUser() {
		return toResponse(currentUser());
	}

	public UserResponse updateBudget(Double groceryBudget) {
		User user = currentUser();
		user.setGroceryBudget(groceryBudget);
		return toResponse(userRepository.save(user));
	}

	public String getSpendingReport() {
		JsonNode report = currentUser().getSpendingReport();
		return report == null ? null : report.toString();
	}

	@Transactional
	public String updateSpendingReport(String spendingReport) {
		User user = currentUser();
		String normalized = normalizeSpendingReport(spendingReport);
		userRepository.updateSpendingReport(user.getUserId(), normalized);
		return normalized;
	}

	private static String normalizeSpendingReport(String spendingReport) {
		if (spendingReport == null || spendingReport.isBlank()) {
			return null;
		}
		try {
			JsonNode node = OBJECT_MAPPER.readTree(spendingReport);
			return node == null || node.isNull() ? null : node.toString();
		} catch (JsonProcessingException ex) {
			throw new InvalidSpendingReportException(ex);
		}
	}

	private User currentUser() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
			throw new BadCredentialsException();
		}
		try {
			return userRepository.findById(java.util.UUID.fromString(jwtAuth.getToken().getSubject()))
				.orElseThrow(UserNotFoundException::new);
		} catch (IllegalArgumentException ex) {
			throw new BadCredentialsException(ex);
		}
	}

	public record Tokens(AuthResponse body, String refreshToken) {
	}

	public static class EmailAlreadyExistsException extends RuntimeException {
	}

	public static class BadCredentialsException extends RuntimeException {
		public BadCredentialsException() {
			super();
		}

		public BadCredentialsException(Throwable cause) {
			super(cause);
		}
	}

	public static class UserNotFoundException extends RuntimeException {
	}

	public static class InvalidSpendingReportException extends RuntimeException {
		public InvalidSpendingReportException(Throwable cause) {
			super(cause);
		}
	}
}
