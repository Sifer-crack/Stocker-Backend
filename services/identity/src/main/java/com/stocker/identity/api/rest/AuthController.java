package com.stocker.identity.api.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.api.rest.dto.LoginRequest;
import com.stocker.identity.api.rest.dto.RegisterRequest;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.application.AuthService;
import com.stocker.identity.infrastructure.security.RefreshTokenCookieFactory;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/identity")
public class AuthController {

	private final AuthService authService;
	private final RefreshTokenCookieFactory refreshTokenCookieFactory;

	public AuthController(AuthService authService, RefreshTokenCookieFactory refreshTokenCookieFactory) {
		this.authService = authService;
		this.refreshTokenCookieFactory = refreshTokenCookieFactory;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
		AuthService.Tokens tokens = authService.login(request);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.create(tokens.refreshToken()).toString())
			.body(tokens.body());
	}

	@PostMapping("/refresh")
	public ResponseEntity<AuthResponse> refresh(
		@CookieValue(value = "refreshToken", required = false) String refreshToken) {
		AuthService.Tokens tokens = authService.refresh(refreshToken);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.create(tokens.refreshToken()).toString())
			.body(tokens.body());
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout() {
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.clear().toString())
			.build();
	}

	@ExceptionHandler(AuthService.EmailAlreadyExistsException.class)
	public ResponseEntity<ErrorBody> emailExists() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("email already registered"));
	}

	@ExceptionHandler(AuthService.BadCredentialsException.class)
	public ResponseEntity<ErrorBody> badCredentials() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody("invalid credentials"));
	}

	public record ErrorBody(String error) {
	}
}
