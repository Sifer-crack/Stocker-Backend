package com.stocker.notifications.application.port;

import com.stocker.notifications.domain.ExpirationNotification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExpirationNotificationRepository {

	ExpirationNotification save(ExpirationNotification notification);

	boolean existsByPantryItemIdAndExpirationDate(UUID pantryItemId, LocalDate expirationDate);

	List<ExpirationNotification> findByUserIdNewestFirst(UUID userId);

	Optional<ExpirationNotification> findByIdAndUserId(UUID id, UUID userId);
}
