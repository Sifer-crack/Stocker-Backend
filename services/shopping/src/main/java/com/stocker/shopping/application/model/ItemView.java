package com.stocker.shopping.application.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The complete client-facing state of a shopping-list item, including its comparison. This exact
 * shape is returned by the REST API and pushed to the gateway, so a client never has to merge a
 * partial update with stale data. {@code cheapest} is null unless {@code comparisonStatus} is
 * "available"; {@code prices} lists every compared (chain, store) price, ascending.
 */
public record ItemView(UUID id, String name, String sku, String category, String region, int quantity,
		String comparisonStatus, PriceView cheapest, List<PriceView> prices, OffsetDateTime comparedAt,
		OffsetDateTime createdAt) {
}
