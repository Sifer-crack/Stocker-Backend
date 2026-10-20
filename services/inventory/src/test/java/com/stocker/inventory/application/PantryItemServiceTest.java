package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PantryItemServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private PantryItemRepository repository;
    private PantryItemService service;

    @BeforeEach
    void setUp() {
        repository = mock(PantryItemRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new PantryItemService(repository);
    }

    private PantryItem saved() {
        ArgumentCaptor<PantryItem> captor = ArgumentCaptor.forClass(PantryItem.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // acceptance: adding a food item with an expiration date saves the date
    @Test
    void addingAnItemSavesItsExpirationDate() {
        when(repository.findByUserIdAndProductId(userId, productId)).thenReturn(Optional.empty());

        service.addPantryItem(userId, productId, 2, TODAY.plusDays(10), null);

        assertThat(saved().getExpirationDate()).isEqualTo(TODAY.plusDays(10));
    }

    // acceptance: setting a notification threshold saves the threshold
    @Test
    void addingAnItemSavesItsNotificationThreshold() {
        when(repository.findByUserIdAndProductId(userId, productId)).thenReturn(Optional.empty());

        service.addPantryItem(userId, productId, 1, TODAY.plusDays(10), 5);

        assertThat(saved().getNotifyDaysBefore()).isEqualTo(5);
    }

    @Test
    void addingWithoutExpiryStillWorks() {
        when(repository.findByUserIdAndProductId(userId, productId)).thenReturn(Optional.empty());

        service.addPantryItem(userId, productId, 1);

        PantryItem item = saved();
        assertThat(item.getExpirationDate()).isNull();
        assertThat(item.getNotifyDaysBefore()).isNull();
    }

    @Test
    void addingAnExistingProductMergesQuantityAndKeepsItsExpiry() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndProductId(userId, productId)).thenReturn(Optional.of(
                new PantryItem(id, userId, productId, 1, TODAY.plusDays(4), 2)));

        service.addPantryItem(userId, productId, 2, null, null);

        PantryItem item = saved();
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getExpirationDate()).isEqualTo(TODAY.plusDays(4));
        assertThat(item.getNotifyDaysBefore()).isEqualTo(2);
    }

    @Test
    void addingAnExistingProductWithNewExpiryReplacesIt() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndProductId(userId, productId)).thenReturn(Optional.of(
                new PantryItem(id, userId, productId, 1, TODAY.plusDays(4), 2)));

        service.addPantryItem(userId, productId, 1, TODAY.plusDays(9), 4);

        PantryItem item = saved();
        assertThat(item.getExpirationDate()).isEqualTo(TODAY.plusDays(9));
        assertThat(item.getNotifyDaysBefore()).isEqualTo(4);
    }

    @Test
    void rejectsANegativeThreshold() {
        assertThatThrownBy(() -> service.addPantryItem(userId, productId, 1, TODAY.plusDays(3), -1))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsAThresholdWithoutAnExpirationDate() {
        assertThatThrownBy(() -> service.addPantryItem(userId, productId, 1, null, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiration");
        verify(repository, never()).save(any());
    }

    @Test
    void updatingQuantityKeepsTheExpiry() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(
                new PantryItem(id, userId, productId, 1, TODAY.plusDays(4), 2)));

        service.updatePantryItem(userId, id, 6);

        PantryItem item = saved();
        assertThat(item.getQuantity()).isEqualTo(6);
        assertThat(item.getExpirationDate()).isEqualTo(TODAY.plusDays(4));
        assertThat(item.getNotifyDaysBefore()).isEqualTo(2);
    }

    // acceptance: a pantry item with an expiration date can have its threshold set
    @Test
    void updateExpirySavesDateAndThreshold() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(new PantryItem(id, userId, productId, 3)));

        PantryItem result = service.updateExpiry(userId, id, TODAY.plusDays(8), 6);

        assertThat(result.getQuantity()).isEqualTo(3);
        assertThat(saved().getExpirationDate()).isEqualTo(TODAY.plusDays(8));
        assertThat(saved().getNotifyDaysBefore()).isEqualTo(6);
    }

    @Test
    void updateExpiryWithNullDateClearsBothFields() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.of(
                new PantryItem(id, userId, productId, 3, TODAY.plusDays(2), 2)));

        service.updateExpiry(userId, id, null, null);

        PantryItem item = saved();
        assertThat(item.getExpirationDate()).isNull();
        assertThat(item.getNotifyDaysBefore()).isNull();
    }

    @Test
    void updateExpiryOfUnknownItemFails() {
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateExpiry(userId, id, TODAY, 1))
                .isInstanceOf(PantryItemNotFoundException.class);
    }

    @Test
    void dueForNotificationOnlyReturnsItemsInsideTheirThreshold() {
        PantryItem inside = new PantryItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1, TODAY.plusDays(2), 3);
        PantryItem outside = new PantryItem(UUID.randomUUID(), userId, UUID.randomUUID(), 1, TODAY.plusDays(9), 3);
        when(repository.findAllWithExpirationDate()).thenReturn(List.of(inside, outside));

        assertThat(service.findDueForNotification(TODAY)).containsExactly(inside);
    }
}
