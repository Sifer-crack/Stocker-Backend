package com.stocker.notifications.application;

import com.stocker.notifications.application.model.ExpiringItem;
import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.application.port.PantryClient;
import com.stocker.notifications.domain.ExpirationNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
	private final int skipExpiredOlderThanDays;

	public ExpirationScanService(PantryClient pantryClient, ExpirationNotificationRepository repository, Clock clock) {
		this(pantryClient, repository, clock, 7);
	}

	@Autowired
	public ExpirationScanService(PantryClient pantryClient, ExpirationNotificationRepository repository, Clock clock,
			@Value("${app.notifications.skip-expired-older-than-days:7}") int skipExpiredOlderThanDays) {
		this.pantryClient = pantryClient;
		this.repository = repository;
		this.clock = clock;
		this.skipExpiredOlderThanDays = skipExpiredOlderThanDays;
	}

	/** @return how many new notifications were created */
	public int scan() {
		LocalDate today = LocalDate.now(clock);
		LocalDate cutOff = today.minusDays(skipExpiredOlderThanDays);
		int created = 0;
		int failed = 0;
		for (ExpiringItem item : pantryClient.findDueItems(today)) {
			try {
				if (!item.isDue(today)) {
					continue;
				}
				// first scan on an old pantry: do not announce items that expired long ago
				if (item.expirationDate().isBefore(cutOff)) {
					continue;
				}
				if (repository.existsByPantryItemIdAndExpirationDate(item.pantryItemId(), item.expirationDate())) {
					continue;
				}
				repository.save(ExpirationNotification.create(item.userId(), item.pantryItemId(), item.productId(),
						item.expirationDate(), clock.instant(), item.name(), item.category(), item.unit()));
				created++;
			}
			catch (RuntimeException e) {
				failed++;
				log.warn("Expiry scan could not process pantry item {}: {}", item.pantryItemId(), e.toString());
			}
		}
		if (failed > 0) {
			log.warn("Expiry scan for {}: {} item(s) failed and were skipped", today, failed);
		}
		if (created > 0) {
			log.info("Expiry scan for {} created {} notification(s)", today, created);
		}
		return created;
	}
}
