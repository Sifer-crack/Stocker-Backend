package com.stocker.identity.api.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
	@NotBlank String refreshToken
) {
}
