package com.stocker.notifications.application;

import com.stocker.notifications.application.model.ExpiringItem;
import com.stocker.notifications.application.port.PantryClient;
import com.stocker.notifications.domain.ExpirationNotification;
import com.stocker.notifications.domain.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How the notification feature is used day to day: real services, in-memory repository, a moving clock. */
@Tag("service")
class ExpiryNotificationScenarioTest {

    private final UUID userId = UUID.randomUUID();
    private final List<ExpiringItem> pantry = new ArrayList<>();
    private MutableClock clock;
    private InMemoryNotificationRepository repository;
    private ExpirationScanService scan;
    private NotificationService notifications;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-19T01:00:00Z"));
        repository = new InMemoryNotificationRepository();
        // behaves like inventory: only items whose notification window has opened
        PantryClient inventory = asOf -> pantry.stream().filter(item -> item.isDue(asOf)).toList();
        scan = new ExpirationScanService(inventory, repository, clock);
        notifications = new NotificationService(repository, clock);
    }

    private void nextDay() {
        clock.advance(Duration.ofDays(1));
    }

    @Test
    void oneNotificationIsDeliveredOnTheThresholdDayAndNeverRepeated() {
        pantry.add(new ExpiringItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1,
                LocalDate.of(2026, 10, 22), 2));

        assertThat(scan.scan()).isZero();                       // 19th: 3 days out, window opens on the 20th
        nextDay();
        assertThat(scan.scan()).isEqualTo(1);                   // 20th: exactly 2 days before -> delivered on time
        nextDay();
        assertThat(scan.scan()).isZero();                       // 21st: same item, no duplicate
        nextDay();
        assertThat(scan.scan()).isZero();                       // 22nd: expiry day, still no duplicate

        assertThat(notifications.list(userId)).hasSize(1);
    }

    @Test
    void acknowledgedNotificationStaysAcknowledgedAcrossLaterScans() {
        pantry.add(new ExpiringItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1,
                LocalDate.of(2026, 10, 20), 3));
        scan.scan();
        ExpirationNotification first = notifications.list(userId).get(0);

        notifications.acknowledge(userId, first.getId());
        nextDay();
        scan.scan();

        List<ExpirationNotification> all = notifications.list(userId);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getStatus()).isEqualTo(NotificationStatus.ACKNOWLEDGED);
    }

    @Test
    void usersOnlySeeAndAcknowledgeTheirOwnNotifications() {
        UUID other = UUID.randomUUID();
        pantry.add(new ExpiringItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1, LocalDate.of(2026, 10, 20), 3));
        pantry.add(new ExpiringItem(UUID.randomUUID(), other, UUID.randomUUID(), 1, LocalDate.of(2026, 10, 20), 3));
        assertThat(scan.scan()).isEqualTo(2);

        assertThat(notifications.list(userId)).hasSize(1);
        UUID othersId = notifications.list(other).get(0).getId();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> notifications.acknowledge(userId, othersId))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    @Test
    void itemsWithoutExpiryAreNeverNotified() {
        pantry.add(new ExpiringItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1, null, 3));

        assertThat(scan.scan()).isZero();
        nextDay();
        assertThat(scan.scan()).isZero();
        assertThat(repository.size()).isZero();
    }
}
