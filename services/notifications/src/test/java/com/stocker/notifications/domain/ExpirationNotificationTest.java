package com.stocker.notifications.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirationNotificationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");

    private static ExpirationNotification create() {
        return ExpirationNotification.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 10, 21), NOW);
    }

    @Test
    void newNotificationIsUnacknowledged() {
        ExpirationNotification notification = create();

        assertThat(notification.getId()).isNotNull();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(notification.isAcknowledged()).isFalse();
        assertThat(notification.getAcknowledgedAt()).isNull();
        assertThat(notification.getCreatedAt()).isEqualTo(NOW);
    }

    // acceptance: acknowledging marks the notification as acknowledged
    @Test
    void acknowledgeMarksItAcknowledged() {
        ExpirationNotification notification = create();

        notification.acknowledge(NOW.plusSeconds(60));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.ACKNOWLEDGED);
        assertThat(notification.isAcknowledged()).isTrue();
        assertThat(notification.getAcknowledgedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void acknowledgingTwiceKeepsTheFirstTimestamp() {
        ExpirationNotification notification = create();

        notification.acknowledge(NOW.plusSeconds(60));
        notification.acknowledge(NOW.plusSeconds(600));

        assertThat(notification.getAcknowledgedAt()).isEqualTo(NOW.plusSeconds(60));
    }
}
