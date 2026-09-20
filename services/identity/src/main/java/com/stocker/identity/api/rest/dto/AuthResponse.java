package com.stocker.identity.api.rest.dto;

public record AuthResponse(
	String accessToken,
	String tokenType,
	long expiresIn
) {
}
