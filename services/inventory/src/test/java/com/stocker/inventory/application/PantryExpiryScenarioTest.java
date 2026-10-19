package com.stocker.inventory.application;

import com.stocker.inventory.domain.PantryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How the pantry service is used for expiry: real service, in-memory repository. */
@Tag("service")
class PantryExpiryScenarioTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private final UUID userId = UUID.randomUUID();
    private PantryItemService service;

    @BeforeEach
    void setUp() {
        service = new PantryItemService(new InMemoryPantryItemRepository());
    }

    @Test
    void itemBecomesDueWhenItsThresholdIsReached() {
        PantryItem milk = service.addPantryItem(userId, UUID.randomUUID(), 1, TODAY.plusDays(5), 2);

        assertThat(service.findDueForNotification(TODAY)).isEmpty();
        assertThat(service.findDueForNotification(TODAY.plusDays(2))).isEmpty();
        assertThat(service.findDueForNotification(TODAY.plusDays(3))).extracting(PantryItem::getPantry_item_id)
                .containsExactly(milk.getPantry_item_id());
    }

    @Test
    void itemWithoutExpiryIsNeverDue() {
        service.addPantryItem(userId, UUID.randomUUID(), 4);

        assertThat(service.findDueForNotification(TODAY.plusYears(5))).isEmpty();
    }

    @Test
    void raisingTheThresholdPullsAnItemIntoTheWindow() {
        PantryItem bread = service.addPantryItem(userId, UUID.randomUUID(), 1, TODAY.plusDays(6), 1);
        assertThat(service.findDueForNotification(TODAY)).isEmpty();

        service.updateExpiry(bread.getPantry_item_id(), TODAY.plusDays(6), 7);

        assertThat(service.findDueForNotification(TODAY)).hasSize(1);
    }
}
