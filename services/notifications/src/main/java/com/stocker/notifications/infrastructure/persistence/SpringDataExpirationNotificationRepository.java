package com.stocker.notifications.infrastructure.persistence;

import com.stocker.notifications.domain.ExpirationNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataExpirationNotificationRepository extends JpaRepository<ExpirationNotification, UUID> {

	boolean existsByPantryItemIdAndExpirationDate(UUID pantryItemId, LocalDate expirationDate);

	List<ExpirationNotification> findByUserIdOrderByCreatedAtDesc(UUID userId);

	Optional<ExpirationNotification> findByIdAndUserId(UUID id, UUID userId);
}
