package com.stocker.notifications.application.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A pantry item as reported by inventory's GET /internal/pantry-items/expiring (field names are the contract).
 * Inventory only lists items whose window has opened; {@link #isDue} lets this service double-check.
 */
public record ExpiringItem(UUID pantryItemId, UUID userId, UUID productId, int quantity, LocalDate expirationDate,
		Integer notifyDaysBefore) {

	/** Same default as inventory's PantryItem.DEFAULT_NOTIFY_DAYS_BEFORE. */
	static final int DEFAULT_NOTIFY_DAYS_BEFORE = 3;

	public boolean isDue(LocalDate today) {
		if (expirationDate == null) {
			return false;
		}
		int days = notifyDaysBefore != null ? notifyDaysBefore : DEFAULT_NOTIFY_DAYS_BEFORE;
		return !today.isBefore(expirationDate.minusDays(days));
	}
}
