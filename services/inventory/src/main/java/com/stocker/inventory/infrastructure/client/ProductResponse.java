package com.stocker.inventory.infrastructure.client;

import java.util.UUID;

public record ProductResponse(
        UUID productId,
        UUID categoryId,
        String name,
        String unit) {
}
