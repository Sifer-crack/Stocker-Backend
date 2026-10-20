package com.stocker.notifications.application;

import com.stocker.notifications.application.model.ExpiringItem;
import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.application.port.PantryClient;
import com.stocker.notifications.domain.ExpirationNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/** Turns "items inside their expiry window" into notifications, at most one per item and expiration date. */
@Service
public class ExpirationScanService {

	private static final Logger log = LoggerFactory.getLogger(ExpirationScanService.class);

	private final PantryClient pantryClient;
	private final ExpirationNotificationRepository repository;
	private final Clock clock;

	public ExpirationScanService(PantryClient pantryClient, ExpirationNotificationRepository repository, Clock clock) {
		this.pantryClient = pantryClient;
		this.repository = repository;
		this.clock = clock;
	}

	/** @return how many new notifications were created */
	public int scan() {
		LocalDate today = LocalDate.now(clock);
		int created = 0;
		for (ExpiringItem item : pantryClient.findDueItems(today)) {
			if (!item.isDue(today)) {
				continue;
			}
			if (repository.existsByPantryItemIdAndExpirationDate(item.pantryItemId(), item.expirationDate())) {
				continue;
			}
			repository.save(ExpirationNotification.create(item.userId(), item.pantryItemId(), item.productId(),
					item.expirationDate(), clock.instant()));
			created++;
		}
		if (created > 0) {
			log.info("Expiry scan for {} created {} notification(s)", today, created);
		}
		return created;
	}
}
