package com.stocker.catalog.infrastructure.persistence;


import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "product_item_mappings")
public class ProductItemMappingEntity {
    @EmbeddedId
    private ProductItemMappingId id;

    public ProductItemMappingEntity() {
    }

    public ProductItemMappingEntity(UUID productId, Integer itemId) {
        this.id = new ProductItemMappingId(productId, itemId);
    }

    public ProductItemMappingId getId() {
        return id;
    }

    public void setId(ProductItemMappingId id) {
        this.id = id;
    }
}
