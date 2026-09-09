package com.stocker.pantry.domain;

import java.util.UUID;

public class PantryItem {
    private final UUID pantry_item_id;
    private final UUID user_id;
    private final UUID product_id;
    private final int quantity;

    public PantryItem(UUID pantry_item_id, UUID user_id, UUID product_id, int quantity) {
        this.pantry_item_id = pantry_item_id;
        this.user_id = user_id;
        this.product_id = product_id;
        this.quantity = quantity;
    }

    public UUID getPantry_item_id() {
        return pantry_item_id;
    }

    public UUID getUser_id() {
        return user_id;
    }

    public UUID getProduct_id() {
        return product_id;
    }

    public int getQuantity() {
        return quantity;
    }
}
