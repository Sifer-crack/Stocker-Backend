package com.stocker.notifications.application;

import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.domain.ExpirationNotification;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

	private final ExpirationNotificationRepository repository;
	private final Clock clock;

	public NotificationService(ExpirationNotificationRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	public List<ExpirationNotification> list(UUID userId) {
		return repository.findByUserIdNewestFirst(userId);
	}

	/** Marks the user's own notification acknowledged; someone else's (or unknown) is "not found". */
	public ExpirationNotification acknowledge(UUID userId, UUID notificationId) {
		ExpirationNotification notification = repository.findByIdAndUserId(notificationId, userId)
				.orElseThrow(() -> new NotificationNotFoundException(notificationId));
		notification.acknowledge(clock.instant());
		return repository.save(notification);
	}
}
