package com.stocker.notifications.application.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A pantry item as reported by inventory's GET /internal/pantry-items/expiring (field names are the contract;
 * productId is null for free-text items, which carry name/category/unit instead).
 * Inventory only lists items whose window has opened; {@link #isDue} lets this service double-check.
 */
public record ExpiringItem(UUID pantryItemId, UUID userId, UUID productId, int quantity, LocalDate expirationDate,
		Integer notifyDaysBefore, String name, String category, String unit) {

	/** Without name/category/unit (older inventory payloads). */
	public ExpiringItem(UUID pantryItemId, UUID userId, UUID productId, int quantity, LocalDate expirationDate,
			Integer notifyDaysBefore) {
		this(pantryItemId, userId, productId, quantity, expirationDate, notifyDaysBefore, null, null, null);
	}

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
