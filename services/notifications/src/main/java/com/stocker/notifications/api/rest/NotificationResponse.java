package com.stocker.notifications.api.rest;

import com.stocker.notifications.domain.ExpirationNotification;
import com.stocker.notifications.domain.NotificationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record NotificationResponse(UUID id, UUID pantryItemId, UUID productId, String itemName,
		String category, String unit, LocalDate expirationDate,
		NotificationStatus status, Instant createdAt, Instant acknowledgedAt) {

	static NotificationResponse from(ExpirationNotification notification) {
		return new NotificationResponse(notification.getId(), notification.getPantryItemId(),
				notification.getProductId(), notification.getItemName(), notification.getCategory(),
				notification.getUnit(), notification.getExpirationDate(), notification.getStatus(),
				notification.getCreatedAt(), notification.getAcknowledgedAt());
	}
}
