package com.stocker.identity.api.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.stocker.identity.api.rest.dto.AuthResponse;
import com.stocker.identity.api.rest.dto.LoginRequest;
import com.stocker.identity.api.rest.dto.RefreshRequest;
import com.stocker.identity.api.rest.dto.RegisterRequest;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.application.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/identity")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request);
	}

	@ExceptionHandler(AuthService.EmailAlreadyExistsException.class)
	public ResponseEntity<ErrorBody> emailExists() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("email already registered"));
	}

	@ExceptionHandler(AuthService.UsernameAlreadyExistsException.class)
	public ResponseEntity<ErrorBody> usernameExists() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody("username already taken"));
	}

	@ExceptionHandler(AuthService.BadCredentialsException.class)
	public ResponseEntity<ErrorBody> badCredentials() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody("invalid credentials"));
	}

	public record ErrorBody(String error) {
	}
}
