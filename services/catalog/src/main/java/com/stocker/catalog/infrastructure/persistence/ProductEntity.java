package com.stocker.catalog.infrastructure.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "products")
public class ProductEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "grocery_type")
    private String groceryType;

    @Column(name = "selling_weight_kg", precision = 10, scale = 4)
    private BigDecimal sellingWeightKg;

    @Column(name = "selling_volume_l", precision = 10, scale = 4)
    private BigDecimal sellingVolumeL;

    @Column(name = "created_at", insertable = false)
    private OffsetDateTime createdAt;

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getGroceryType() {
        return groceryType;
    }

    public void setGroceryType(String groceryType) {
        this.groceryType = groceryType;
    }

    public BigDecimal getSellingWeightKg() {
        return sellingWeightKg;
    }

    public void setSellingWeightKg(BigDecimal sellingWeightKg) {
        this.sellingWeightKg = sellingWeightKg;
    }

    public BigDecimal getSellingVolumeL() {
        return sellingVolumeL;
    }

    public void setSellingVolumeL(BigDecimal sellingVolumeL) {
        this.sellingVolumeL = sellingVolumeL;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

}
