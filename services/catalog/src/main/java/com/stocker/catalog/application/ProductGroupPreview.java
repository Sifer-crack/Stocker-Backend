package com.stocker.catalog.application;

import com.stocker.catalog.infrastructure.persistence.ItemEntity;

import java.math.BigDecimal;
import java.util.List;

public record ProductGroupPreview (
        String normalizedName,
        String groceryType,
        BigDecimal weightKg,
        BigDecimal volumeL,
        int itemCount,
        boolean needsReview,
        List<ItemEntity> items
        ){
}
