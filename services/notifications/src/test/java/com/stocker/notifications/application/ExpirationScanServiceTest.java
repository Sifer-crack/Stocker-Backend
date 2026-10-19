package com.stocker.notifications.application;

import com.stocker.notifications.application.model.ExpiringItem;
import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.application.port.PantryClient;
import com.stocker.notifications.domain.ExpirationNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpirationScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private PantryClient pantryClient;
    private ExpirationNotificationRepository repository;
    private ExpirationScanService service;

    @BeforeEach
    void setUp() {
        pantryClient = mock(PantryClient.class);
        repository = mock(ExpirationNotificationRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new ExpirationScanService(pantryClient, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExpiringItem item(LocalDate expiry, Integer threshold) {
        return new ExpiringItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, expiry, threshold);
    }

    // acceptance: item within the threshold -> the user receives an expiration notification
    @Test
    void createsANotificationForAnItemInsideItsThreshold() {
        ExpiringItem due = item(TODAY.plusDays(2), 3);
        when(pantryClient.findDueItems(TODAY)).thenReturn(List.of(due));

        int created = service.scan();

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<ExpirationNotification> captor = ArgumentCaptor.forClass(ExpirationNotification.class);
        verify(repository).save(captor.capture());
        ExpirationNotification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(due.userId());
        assertThat(saved.getPantryItemId()).isEqualTo(due.pantryItemId());
        assertThat(saved.getExpirationDate()).isEqualTo(TODAY.plusDays(2));
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    // acceptance: item outside the threshold -> no notification
    @Test
    void ignoresAnItemOutsideItsThreshold() {
        when(pantryClient.findDueItems(TODAY)).thenReturn(List.of(item(TODAY.plusDays(10), 3)));

        assertThat(service.scan()).isZero();
        verify(repository, never()).save(any());
    }

    // acceptance: item with no expiration date -> no notification
    @Test
    void ignoresAnItemWithoutAnExpirationDate() {
        when(pantryClient.findDueItems(TODAY)).thenReturn(List.of(item(null, 3)));

        assertThat(service.scan()).isZero();
        verify(repository, never()).save(any());
    }

    // acceptance: no duplicate notification for the same item
    @Test
    void doesNotNotifyTwiceForTheSameItemAndExpiryDate() {
        ExpiringItem due = item(TODAY.plusDays(1), 3);
        when(pantryClient.findDueItems(TODAY)).thenReturn(List.of(due));
        when(repository.existsByPantryItemIdAndExpirationDate(due.pantryItemId(), due.expirationDate()))
                .thenReturn(true);

        assertThat(service.scan()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void aNewExpiryDateOnTheSameItemNotifiesAgain() {
        UUID pantryItemId = UUID.randomUUID();
        ExpiringItem restocked = new ExpiringItem(pantryItemId, UUID.randomUUID(), UUID.randomUUID(), 1,
                TODAY.plusDays(2), 3);
        when(pantryClient.findDueItems(TODAY)).thenReturn(List.of(restocked));
        when(repository.existsByPantryItemIdAndExpirationDate(pantryItemId, TODAY.minusDays(5))).thenReturn(true);

        assertThat(service.scan()).isEqualTo(1);
    }

    @Test
    void asksInventoryForTodayInTheClockZone() {
        when(pantryClient.findDueItems(TODAY)).thenReturn(List.of());

        service.scan();

        verify(pantryClient).findDueItems(TODAY);
    }
}
