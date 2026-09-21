package com.stocker.catalog.domain;

import java.math.BigDecimal;

public record ProductGroupKey(
        String normalizedName,
        String groceryType,
        BigDecimal weightKg,
        BigDecimal volumeL
        ) {
}
