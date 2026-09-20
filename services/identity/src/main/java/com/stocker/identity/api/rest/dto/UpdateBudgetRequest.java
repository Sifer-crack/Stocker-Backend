package com.stocker.identity.api.rest.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record UpdateBudgetRequest(
	@PositiveOrZero Double groceryBudget
) {
}
