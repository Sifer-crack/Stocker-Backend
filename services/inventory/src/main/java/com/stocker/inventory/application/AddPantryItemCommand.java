package com.stocker.inventory.application;

import java.time.LocalDate;
import java.util.UUID;

/** Input for adding a pantry item. Either {@code productId} (catalog) or {@code name} (free text) is required. */
public record AddPantryItemCommand(UUID userId, UUID productId, String name, String category, String unit,
                                   int quantity, LocalDate expirationDate, Integer notifyDaysBefore) {
}
