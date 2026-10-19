package com.stocker.inventory.api.rest;

import com.stocker.inventory.domain.PantryItem;

import java.time.LocalDate;
import java.util.UUID;

/** Contract with the notifications service (GET /pantry-items/expiring). Field names are shared; keep in sync. */
public record ExpiringPantryItemResponse(UUID pantryItemId, UUID userId, UUID productId, int quantity,
                                         LocalDate expirationDate, Integer notifyDaysBefore) {

    static ExpiringPantryItemResponse from(PantryItem item) {
        return new ExpiringPantryItemResponse(item.getPantry_item_id(), item.getUser_id(), item.getProduct_id(),
                item.getQuantity(), item.getExpirationDate(), item.getNotifyDaysBefore());
    }
}
