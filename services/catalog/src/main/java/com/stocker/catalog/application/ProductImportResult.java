package com.stocker.catalog.application;

public record ProductImportResult(
        int productsCreated,
        int mappingsCreated
        ) {
}
