package com.stocker.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** "Your pantry item is about to expire", one per (pantry item, expiration date). */
@Entity
@Table(name = "expiration_notifications")
public class ExpirationNotification {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "pantry_item_id", nullable = false)
	private UUID pantryItemId;

	@Column(name = "product_id")
	private UUID productId;

	@Column(name = "expiration_date", nullable = false)
	private LocalDate expirationDate;

	@Column(name = "item_name", length = 255)
	private String itemName;

	@Column(length = 100)
	private String category;

	@Column(length = 50)
	private String unit;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "acknowledged_at")
	private Instant acknowledgedAt;

	protected ExpirationNotification() {
	}

	public static ExpirationNotification create(UUID userId, UUID pantryItemId, UUID productId,
			LocalDate expirationDate, Instant now) {
		return create(userId, pantryItemId, productId, expirationDate, now, null, null, null);
	}

	/** productId is null for free-text pantry items; itemName/category/unit come from inventory. */
	public static ExpirationNotification create(UUID userId, UUID pantryItemId, UUID productId,
			LocalDate expirationDate, Instant now, String itemName, String category, String unit) {
		ExpirationNotification notification = new ExpirationNotification();
		notification.id = UUID.randomUUID();
		notification.userId = userId;
		notification.pantryItemId = pantryItemId;
		notification.productId = productId;
		notification.expirationDate = expirationDate;
		notification.itemName = itemName;
		notification.category = category;
		notification.unit = unit;
		notification.status = NotificationStatus.NEW;
		notification.createdAt = now;
		return notification;
	}

	/** Idempotent: a second acknowledgement keeps the time of the first. */
	public void acknowledge(Instant now) {
		if (status == NotificationStatus.ACKNOWLEDGED) {
			return;
		}
		status = NotificationStatus.ACKNOWLEDGED;
		acknowledgedAt = now;
	}

	public boolean isAcknowledged() {
		return status == NotificationStatus.ACKNOWLEDGED;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getPantryItemId() {
		return pantryItemId;
	}

	public UUID getProductId() {
		return productId;
	}

	public String getItemName() {
		return itemName;
	}

	public String getCategory() {
		return category;
	}

	public String getUnit() {
		return unit;
	}

	public LocalDate getExpirationDate() {
		return expirationDate;
	}

	public NotificationStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getAcknowledgedAt() {
		return acknowledgedAt;
	}
}
