package com.stocker.catalog.infrastructure.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;


@Entity
@Table(name = "items")
public class ItemEntity {
    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "grocery_type", nullable = false)
    private String groceryType;

    @Column(name = "grocery_name", nullable = false)
    private String groceryName;

    @Column(name = "store_id")
    private String storeId;

    @Column(name = "price_tag", precision = 10, scale = 2, nullable = false)
    private BigDecimal priceTag;

    @Column(name = "selling_weight_kg", precision = 10, scale = 4)
    private BigDecimal sellingWeightKg;

    @Column(name = "selling_volume_l", precision = 10, scale = 4)
    private BigDecimal sellingVolumeL;

    @Column(name = "price_per_kg", precision = 10, scale = 4)
    private BigDecimal pricePerKg;

    @Column(name = "price_per_litre", precision = 10, scale = 4)
    private BigDecimal pricePerLitre;

    @Column(name = "notes")
    private String notes;

    @Column(name = "needs_review")
    private boolean needsReview;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public Integer getId() {
        return id;
    }

    public String getGroceryType() {
        return groceryType;
    }

    public String getGroceryName() {
        return groceryName;
    }

    public String getStoreId() {
        return storeId;
    }

    public BigDecimal getPriceTag() {
        return priceTag;
    }

    public BigDecimal getSellingWeightKg() {
        return sellingWeightKg;
    }

    public BigDecimal getSellingVolumeL() {
        return sellingVolumeL;
    }

    public BigDecimal getPricePerKg() {
        return pricePerKg;
    }

    public BigDecimal getPricePerLitre() {
        return pricePerLitre;
    }

    public String getNotes() {
        return notes;
    }

    public boolean isNeedsReview() {
        return needsReview;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
