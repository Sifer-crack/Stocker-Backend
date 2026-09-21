package com.stocker.identity.api.rest.dto;

import java.util.UUID;

public record UserResponse(
	UUID id,
	String email,
	String firstName,
	String lastName,
	Double groceryBudget
) {
}
