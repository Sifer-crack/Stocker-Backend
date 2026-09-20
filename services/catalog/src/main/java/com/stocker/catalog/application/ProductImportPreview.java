package com.stocker.catalog.application;

import java.math.BigDecimal;
import java.util.List;

public record ProductImportPreview(
        String productName,
        String groceryType,
        BigDecimal weightKg,
        BigDecimal volumeL,
        int itemCount,
        List<Integer> itemIds
) {
}
