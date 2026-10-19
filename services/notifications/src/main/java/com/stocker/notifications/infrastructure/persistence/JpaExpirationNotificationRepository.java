package com.stocker.notifications.infrastructure.persistence;

import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.domain.ExpirationNotification;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaExpirationNotificationRepository implements ExpirationNotificationRepository {

	private final SpringDataExpirationNotificationRepository repository;

	public JpaExpirationNotificationRepository(SpringDataExpirationNotificationRepository repository) {
		this.repository = repository;
	}

	@Override
	public ExpirationNotification save(ExpirationNotification notification) {
		return repository.save(notification);
	}

	@Override
	public boolean existsByPantryItemIdAndExpirationDate(UUID pantryItemId, LocalDate expirationDate) {
		return repository.existsByPantryItemIdAndExpirationDate(pantryItemId, expirationDate);
	}

	@Override
	public List<ExpirationNotification> findByUserIdNewestFirst(UUID userId) {
		return repository.findByUserIdOrderByCreatedAtDesc(userId);
	}

	@Override
	public Optional<ExpirationNotification> findByIdAndUserId(UUID id, UUID userId) {
		return repository.findByIdAndUserId(id, userId);
	}
}
