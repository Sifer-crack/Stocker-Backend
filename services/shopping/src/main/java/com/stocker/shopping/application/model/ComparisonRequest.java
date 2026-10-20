package com.stocker.shopping.application.model;

import java.util.UUID;

/** What pricing needs to compare an item: the id it will publish events under, plus the lookup terms. */
public record ComparisonRequest(UUID itemId, String searchTerm, String category, String region) {
}
