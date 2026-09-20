package com.stocker.pricing.refresh;

import java.util.List;

/** Payload for stocker.pricing.refresh-requests.v1 — pricing's own internal cache-miss retry queue. */
public record PriceRefreshRequest(String itemId, String searchTerm, List<String> storeUrls, String category) {
}
