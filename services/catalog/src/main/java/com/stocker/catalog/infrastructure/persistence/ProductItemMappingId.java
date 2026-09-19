package com.stocker.catalog.infrastructure.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProductItemMappingId implements Serializable {
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "item_id")
    private Integer itemId;

    public ProductItemMappingId() {
    }

    public ProductItemMappingId(UUID productId, Integer itemId) {
        this.productId = productId;
        this.itemId = itemId;
    }

    public UUID getProductId() {
        return productId;
    }

    public Integer getItemId() {
        return itemId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof ProductItemMappingId that)) {
            return false;
        }

        return Objects.equals(productId, that.productId) && Objects.equals(itemId, that.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId, itemId);
    }

}
