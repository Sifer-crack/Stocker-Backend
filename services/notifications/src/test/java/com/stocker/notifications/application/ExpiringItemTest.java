package com.stocker.notifications.application;

import com.stocker.notifications.application.model.ExpiringItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiringItemTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private static ExpiringItem item(LocalDate expiry, Integer threshold) {
        return new ExpiringItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, expiry, threshold);
    }

    @Test
    void dueExactlyWhenTheThresholdIsReached() {
        assertThat(item(TODAY.plusDays(2), 2).isDue(TODAY)).isTrue();
        assertThat(item(TODAY.plusDays(3), 2).isDue(TODAY)).isFalse();
    }

    @Test
    void neverDueWithoutAnExpirationDate() {
        assertThat(item(null, 5).isDue(TODAY)).isFalse();
    }

    @Test
    void defaultThresholdIsThreeDays() {
        assertThat(item(TODAY.plusDays(3), null).isDue(TODAY)).isTrue();
        assertThat(item(TODAY.plusDays(4), null).isDue(TODAY)).isFalse();
    }

    @Test
    void overdueItemsAreDue() {
        assertThat(item(TODAY.minusDays(1), 0).isDue(TODAY)).isTrue();
    }
}
