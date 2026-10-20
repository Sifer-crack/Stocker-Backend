package com.stocker.shopping.api.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record AddFavoriteStoreRequest(@NotBlank String chainId, String storeId) {
}
