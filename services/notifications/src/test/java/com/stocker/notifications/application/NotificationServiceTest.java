package com.stocker.notifications.application;

import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.domain.ExpirationNotification;
import com.stocker.notifications.domain.NotificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");

    private ExpirationNotificationRepository repository;
    private NotificationService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ExpirationNotificationRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new NotificationService(repository, Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC));
    }

    private ExpirationNotification notification() {
        return ExpirationNotification.create(userId, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 10, 21), NOW);
    }

    @Test
    void listReturnsTheUsersNotifications() {
        List<ExpirationNotification> rows = List.of(notification(), notification());
        when(repository.findByUserIdNewestFirst(userId)).thenReturn(rows);

        assertThat(service.list(userId)).isEqualTo(rows);
    }

    @Test
    void acknowledgeMarksTheNotificationAcknowledgedNow() {
        ExpirationNotification row = notification();
        when(repository.findByIdAndUserId(row.getId(), userId)).thenReturn(Optional.of(row));

        ExpirationNotification result = service.acknowledge(userId, row.getId());

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.ACKNOWLEDGED);
        assertThat(result.getAcknowledgedAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void acknowledgeIsIdempotent() {
        ExpirationNotification row = notification();
        row.acknowledge(NOW.plusSeconds(30));
        when(repository.findByIdAndUserId(row.getId(), userId)).thenReturn(Optional.of(row));

        service.acknowledge(userId, row.getId());

        assertThat(row.getAcknowledgedAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void acknowledgingSomeoneElsesNotificationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acknowledge(userId, id)).isInstanceOf(NotificationNotFoundException.class);
    }
}
