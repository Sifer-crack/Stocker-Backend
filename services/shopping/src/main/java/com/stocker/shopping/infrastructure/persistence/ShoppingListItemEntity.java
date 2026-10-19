package com.stocker.shopping.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "shopping_list_items")
public class ShoppingListItemEntity {
    @Id
    @Column(name = "shopping_list_item_id")
    private UUID shoppingListItemId;

    @Column(name = "shopping_list_id", nullable = false)
    private UUID shoppingListId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(nullable = false)
    private String status = "REQUIRED";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected ShoppingListItemEntity() {
    }

    public ShoppingListItemEntity(
            UUID shoppingListId,
            UUID productId,
            Integer quantity) {
                this.shoppingListItemId = UUID.randomUUID();
                this.shoppingListId = shoppingListId;
                this.productId = productId;
                this.quantity = quantity;
                this.status = "REQUIRED";
                this.createdAt = OffsetDateTime.now();
    }

    public UUID getShoppingListItemId() {
        return shoppingListItemId;
    }

    public UUID getShoppingListId() {
        return shoppingListId;
    }

    public UUID getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public String getStatus() {
        return status;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
