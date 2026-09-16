package com.stocker.identity.application;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.api.rest.dto.LoginRequest;
import com.stocker.identity.api.rest.dto.RefreshRequest;
import com.stocker.identity.api.rest.dto.RegisterRequest;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.domain.User;
import com.stocker.identity.infrastructure.persistence.UserRepository;
import com.stocker.identity.infrastructure.security.JwtService;

@Service
public class AuthService {

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
		if (userRepository.findByUsername(request.username()).isPresent()) {
			throw new UsernameAlreadyExistsException();
		}
		User user = new User();
		user.setUsername(request.username());
		user.setEmail(request.email());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setFirstName(request.firstName());
		user.setLastName(request.lastName());
		User saved = userRepository.save(user);
		return toResponse(saved);
	}

	public AuthResponse login(LoginRequest request) {
		try {
			authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password()));
		} catch (org.springframework.security.core.AuthenticationException ex) {
			throw new BadCredentialsException(ex);
		}
		User user = userRepository.findByEmail(request.email()).orElseThrow(BadCredentialsException::new);
		return tokens(user);
	}

	public AuthResponse refresh(RefreshRequest request) {
		JwtService.ValidatedRefresh validated;
		try {
			validated = jwtService.validateRefreshToken(request.refreshToken());
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

	private AuthResponse tokens(User user) {
		String userId = user.getUserId().toString();
		return new AuthResponse(
			jwtService.issueAccessToken(userId, user.getUsername()),
			jwtService.issueRefreshToken(userId, user.getUsername()),
			"Bearer",
			jwtService.accessTtlSeconds());
	}

	private UserResponse toResponse(User user) {
		return new UserResponse(
			user.getUserId(),
			user.getUsername(),
			user.getEmail(),
			user.getFirstName(),
			user.getLastName());
	}

	public static class EmailAlreadyExistsException extends RuntimeException {
	}

	public static class UsernameAlreadyExistsException extends RuntimeException {
	}

	public static class BadCredentialsException extends RuntimeException {
		public BadCredentialsException() {
			super();
		}

		public BadCredentialsException(Throwable cause) {
			super(cause);
		}
	}
}
