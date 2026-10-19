package com.stocker.inventory.domain;

import java.time.LocalDate;
import java.util.UUID;

public class PantryItem {
    /** Days before expiry a notification opens when the user has not chosen a threshold. */
    public static final int DEFAULT_NOTIFY_DAYS_BEFORE = 3;

    private final UUID pantry_item_id;
    private final UUID user_id;
    private final UUID product_id;
    private final int quantity;
    private final LocalDate expirationDate;
    private final Integer notifyDaysBefore;

    public PantryItem(UUID pantry_item_id, UUID user_id, UUID product_id, int quantity) {
        this(pantry_item_id, user_id, product_id, quantity, null, null);
    }

    public PantryItem(UUID pantry_item_id, UUID user_id, UUID product_id, int quantity,
                      LocalDate expirationDate, Integer notifyDaysBefore) {
        this.pantry_item_id = pantry_item_id;
        this.user_id = user_id;
        this.product_id = product_id;
        this.quantity = quantity;
        this.expirationDate = expirationDate;
        this.notifyDaysBefore = notifyDaysBefore;
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

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public Integer getNotifyDaysBefore() {
        return notifyDaysBefore;
    }

    /** True once {@code today} has reached the notification threshold (and stays true after expiry). */
    public boolean isNotificationDue(LocalDate today) {
        if (expirationDate == null) {
            return false;
        }
        int days = notifyDaysBefore != null ? notifyDaysBefore : DEFAULT_NOTIFY_DAYS_BEFORE;
        return !today.isBefore(expirationDate.minusDays(days));
    }

    /** Same item with the expiry settings replaced (quantity and identity unchanged). */
    public PantryItem withExpiry(LocalDate expirationDate, Integer notifyDaysBefore) {
        return new PantryItem(pantry_item_id, user_id, product_id, quantity, expirationDate, notifyDaysBefore);
    }

    /** Same item with a different quantity (expiry settings unchanged). */
    public PantryItem withQuantity(int quantity) {
        return new PantryItem(pantry_item_id, user_id, product_id, quantity, expirationDate, notifyDaysBefore);
    }
}
