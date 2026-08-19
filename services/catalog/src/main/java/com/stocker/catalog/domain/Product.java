package com.stocker.catalog.domain;

import java.util.UUID;

public class Product {
    private final UUID productId;
    private final UUID categoryId;
    private final String name;
    private final String unit;


    public Product(UUID productId, UUID categoryId, String name, String unit) {
        this.productId = productId;
        this.categoryId = categoryId;
        this.name = name;
        this.unit = unit;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getName() {
        return name;
    }

    public String getUnit() {
        return unit;
    }
}
