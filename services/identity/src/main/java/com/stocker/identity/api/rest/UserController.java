package com.stocker.identity.api.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stocker.identity.api.rest.dto.UpdateBudgetRequest;
import com.stocker.identity.api.rest.dto.UserResponse;
import com.stocker.identity.application.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/identity/me")
public class UserController {

	private final AuthService authService;

	public UserController(AuthService authService) {
		this.authService = authService;
	}

	@GetMapping
	public UserResponse me() {
		return authService.getCurrentUser();
	}

	@PatchMapping
	public UserResponse updateBudget(@Valid @RequestBody UpdateBudgetRequest request) {
		return authService.updateBudget(request.groceryBudget());
	}

	@ExceptionHandler(AuthService.UserNotFoundException.class)
	public ResponseEntity<ErrorBody> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody("user not found"));
	}

	@ExceptionHandler(AuthService.BadCredentialsException.class)
	public ResponseEntity<ErrorBody> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorBody("unauthorized"));
	}

	public record ErrorBody(String error) {
	}
}
