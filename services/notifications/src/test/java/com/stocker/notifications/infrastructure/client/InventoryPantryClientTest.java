package com.stocker.notifications.infrastructure.client;

import com.stocker.notifications.application.model.ExpiringItem;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryPantryClientTest {

    private static final UUID ITEM = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PRODUCT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** The exact shape inventory's PantryItemExpiryControllerTest asserts on GET /internal/pantry-items/expiring. */
    private static final String CONTRACT_JSON = """
            [{"pantryItemId":"%s","userId":"%s","productId":"%s","quantity":2,
              "expirationDate":"2026-10-20","notifyDaysBefore":3}]
            """.formatted(ITEM, USER, PRODUCT);

    @Test
    void readsTheInventoryContractAndSendsTheDate() throws Exception {
        try (InventoryStub inventory = new InventoryStub(query -> InventoryStub.Reply.ok(CONTRACT_JSON))) {
            List<ExpiringItem> items = new InventoryPantryClient(inventory.baseUrl())
                    .findDueItems(LocalDate.of(2026, 10, 19));

            assertThat(inventory.lastPath()).isEqualTo("/internal/pantry-items/expiring");
            assertThat(inventory.lastQuery()).isEqualTo("asOf=2026-10-19");
            assertThat(items).containsExactly(new ExpiringItem(ITEM, USER, PRODUCT, 2,
                    LocalDate.of(2026, 10, 20), 3));
        }
    }

    @Test
    void acceptsNullThresholdAndEmptyList() throws Exception {
        String json = """
                [{"pantryItemId":"%s","userId":"%s","productId":"%s","quantity":1,
                  "expirationDate":"2026-10-20","notifyDaysBefore":null}]
                """.formatted(ITEM, USER, PRODUCT);
        try (InventoryStub inventory = new InventoryStub(query -> InventoryStub.Reply.ok(json))) {
            assertThat(new InventoryPantryClient(inventory.baseUrl()).findDueItems(LocalDate.of(2026, 10, 19)))
                    .singleElement().extracting(ExpiringItem::notifyDaysBefore).isNull();
        }
        try (InventoryStub inventory = new InventoryStub(query -> InventoryStub.Reply.ok("[]"))) {
            assertThat(new InventoryPantryClient(inventory.baseUrl()).findDueItems(LocalDate.of(2026, 10, 19)))
                    .isEmpty();
        }
    }

    @Test
    void aServerErrorSurfacesAsAnException() throws Exception {
        try (InventoryStub inventory = new InventoryStub(query -> new InventoryStub.Reply(500, "{}"))) {
            assertThatThrownBy(() -> new InventoryPantryClient(inventory.baseUrl())
                    .findDueItems(LocalDate.of(2026, 10, 19))).isInstanceOf(RestClientException.class);
        }
    }
}
