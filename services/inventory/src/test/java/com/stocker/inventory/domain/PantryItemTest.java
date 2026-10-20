package com.stocker.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PantryItemTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private static PantryItem item(LocalDate expiry, Integer threshold) {
        return new PantryItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, expiry, threshold);
    }

    @Test
    void dueWhenThresholdIsReached() {
        assertThat(item(TODAY.plusDays(3), 3).isNotificationDue(TODAY)).isTrue();
    }

    @Test
    void dueOnceInsideTheThreshold() {
        assertThat(item(TODAY.plusDays(1), 3).isNotificationDue(TODAY)).isTrue();
    }

    @Test
    void notDueOutsideTheThreshold() {
        assertThat(item(TODAY.plusDays(4), 3).isNotificationDue(TODAY)).isFalse();
    }

    @Test
    void notDueWithoutAnExpirationDate() {
        assertThat(item(null, 3).isNotificationDue(TODAY)).isFalse();
        assertThat(item(null, null).isNotificationDue(TODAY)).isFalse();
    }

    @Test
    void usesTheDefaultThresholdWhenNoneIsSet() {
        assertThat(PantryItem.DEFAULT_NOTIFY_DAYS_BEFORE).isEqualTo(3);
        assertThat(item(TODAY.plusDays(3), null).isNotificationDue(TODAY)).isTrue();
        assertThat(item(TODAY.plusDays(4), null).isNotificationDue(TODAY)).isFalse();
    }

    @Test
    void zeroThresholdIsOnlyDueOnTheExpiryDay() {
        assertThat(item(TODAY, 0).isNotificationDue(TODAY)).isTrue();
        assertThat(item(TODAY.plusDays(1), 0).isNotificationDue(TODAY)).isFalse();
    }

    @Test
    void anOverdueItemStaysDue() {
        assertThat(item(TODAY.minusDays(2), 3).isNotificationDue(TODAY)).isTrue();
    }

    @Test
    void fourArgumentConstructorHasNoExpiry() {
        PantryItem plain = new PantryItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2);
        assertThat(plain.getExpirationDate()).isNull();
        assertThat(plain.getNotifyDaysBefore()).isNull();
    }
}
