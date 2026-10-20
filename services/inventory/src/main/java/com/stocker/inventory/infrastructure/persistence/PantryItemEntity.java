package com.stocker.inventory.infrastructure.persistence;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pantry_items")
public class PantryItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "pantry_item_id")
    private UUID pantryItemId; // creates new id for pantry item

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false)
    private UUID productId; // uses pre-existing id from the product table

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Column(name = "notify_days_before")
    private Integer notifyDaysBefore;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UUID getPantryItemId() {
        return pantryItemId;
    }

    public void setPantryItemId(UUID pantryItemId) {
        this.pantryItemId = pantryItemId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    public Integer getNotifyDaysBefore() {
        return notifyDaysBefore;
    }

    public void setNotifyDaysBefore(Integer notifyDaysBefore) {
        this.notifyDaysBefore = notifyDaysBefore;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

}
