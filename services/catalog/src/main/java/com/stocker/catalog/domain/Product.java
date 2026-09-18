package com.stocker.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;

public class Product {
    private final UUID productId;
    private final String productName;
    private final String groceryType;
    private final BigDecimal sellingWeightKg;
    private final BigDecimal sellingVolumeL;



    public Product(UUID productId, String productName, String groceryType, BigDecimal sellingWeightKg, BigDecimal sellingVolumeL) {
        this.productId = productId;
        this.productName = productName;
        this.groceryType = groceryType;
        this.sellingWeightKg = sellingWeightKg;
        this.sellingVolumeL = sellingVolumeL;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public String getGroceryType() {
        return groceryType;
    }

    public BigDecimal getSellingWeightKg() {
        return sellingWeightKg;
    }

    public BigDecimal getSellingVolumeL() {
        return sellingVolumeL;
    }
}
