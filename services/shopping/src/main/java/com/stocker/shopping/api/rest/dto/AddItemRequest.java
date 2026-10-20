package com.stocker.shopping.api.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code name} is what pricing searches on. {@code region} is the user's location, optional: when
 * given it must be a region pricing serves, otherwise the item ends up "unavailable".
 */
public record AddItemRequest(
		@NotBlank @Size(max = 200) String name,
		@Size(max = 100) String sku,
		@Size(max = 100) String category,
		@Size(max = 100) String region,
		@Min(1) @Max(999) Integer quantity) {
}
