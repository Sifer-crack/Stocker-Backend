package com.stocker.notifications.integration;

import com.stocker.notifications.application.ExpirationScanService;
import com.stocker.notifications.application.NotificationService;
import com.stocker.notifications.application.model.ExpiringItem;
import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.application.port.PantryClient;
import com.stocker.notifications.domain.ExpirationNotification;
import com.stocker.notifications.domain.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real Postgres + Flyway + JPA. Skipped unless STOCKER_NOTIFICATIONS_DB_IT=true; needs a database at
 * STOCKER_DB_URL (default localhost:5432/notifications, stocker/stocker), e.g. a throwaway docker postgres.
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_NOTIFICATIONS_DB_IT", matches = "true")
@SpringBootTest(properties = {
		"spring.kafka.listener.auto-startup=false",
		"app.notifications.scan-initial-delay=PT24H" })
class ExpirationNotificationPostgresIT {

	@Autowired
	private ExpirationNotificationRepository repository;
	@Autowired
	private ExpirationScanService scanService;
	@Autowired
	private NotificationService notificationService;
	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private PantryClient pantryClient;

	private final UUID userId = UUID.randomUUID();

	@BeforeEach
	void clean() {
		jdbc.update("delete from expiration_notifications");
	}

	@Test
	void savedNotificationRoundTripsAndIsFoundByItemAndDate() {
		UUID itemId = UUID.randomUUID();
		ExpirationNotification saved = repository.save(ExpirationNotification.create(userId, itemId,
				UUID.randomUUID(), LocalDate.of(2026, 10, 21), Instant.parse("2026-10-19T01:00:00Z")));

		assertThat(repository.findByIdAndUserId(saved.getId(), userId)).get()
				.extracting(ExpirationNotification::getStatus).isEqualTo(NotificationStatus.NEW);
		assertThat(repository.existsByPantryItemIdAndExpirationDate(itemId, LocalDate.of(2026, 10, 21))).isTrue();
		assertThat(repository.existsByPantryItemIdAndExpirationDate(itemId, LocalDate.of(2026, 10, 22))).isFalse();
		assertThat(repository.findByIdAndUserId(saved.getId(), UUID.randomUUID())).isEmpty();
	}

	@Test
	void theDatabaseItselfRejectsADuplicateForTheSameItemAndDate() {
		UUID itemId = UUID.randomUUID();
		repository.save(ExpirationNotification.create(userId, itemId, UUID.randomUUID(),
				LocalDate.of(2026, 10, 21), Instant.now()));

		assertThatThrownBy(() -> repository.save(ExpirationNotification.create(userId, itemId, UUID.randomUUID(),
				LocalDate.of(2026, 10, 21), Instant.now()))).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void freeTextItemWithNullProductIdPersistsWithNameCategoryUnit() {
		UUID itemId = UUID.randomUUID();
		ExpirationNotification saved = repository.save(ExpirationNotification.create(userId, itemId, null,
				LocalDate.of(2026, 10, 21), Instant.now(), "Nana's jam", "Pantry", "jar"));

		ExpirationNotification loaded = repository.findByIdAndUserId(saved.getId(), userId).orElseThrow();
		assertThat(loaded.getProductId()).isNull();
		assertThat(loaded.getItemName()).isEqualTo("Nana's jam");
		assertThat(loaded.getCategory()).isEqualTo("Pantry");
		assertThat(loaded.getUnit()).isEqualTo("jar");
	}

	@Test
	void scanAcknowledgeAndRescanAgainstPostgres() {
		ExpiringItem due = new ExpiringItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1,
				LocalDate.now().plusDays(1), 3);
		when(pantryClient.findDueItems(any())).thenReturn(List.of(due));

		assertThat(scanService.scan()).isEqualTo(1);
		assertThat(scanService.scan()).isZero();

		ExpirationNotification only = notificationService.list(userId).get(0);
		notificationService.acknowledge(userId, only.getId());

		List<ExpirationNotification> afterAck = notificationService.list(userId);
		assertThat(afterAck).hasSize(1);
		assertThat(afterAck.get(0).getStatus()).isEqualTo(NotificationStatus.ACKNOWLEDGED);
		assertThat(afterAck.get(0).getAcknowledgedAt()).isNotNull();
		assertThat(scanService.scan()).isZero();
	}
}
