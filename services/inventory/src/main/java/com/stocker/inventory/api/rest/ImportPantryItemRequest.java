package com.stocker.inventory.api.rest;

import java.util.UUID;

public record ImportPantryItemRequest(
        UUID product_id,
        int quantity
) {
}