package com.stocker.shopping.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "shopping_lists")
public class ShoppingListEntity {
    @Id
    @Column(name = "shopping_list_id")
    private UUID shoppingListId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected ShoppingListEntity() {
    }

    public ShoppingListEntity(UUID userId, String name) {
        this.shoppingListId = UUID.randomUUID();
        this.userId = userId;
        this.name = name;
        this.status = "ACTIVE";
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getShoppingListId() {
        return shoppingListId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
