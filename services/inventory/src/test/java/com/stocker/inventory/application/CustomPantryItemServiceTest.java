package com.stocker.inventory.application;

import com.stocker.inventory.domain.PantryItem;
import com.stocker.inventory.infrastructure.client.CatalogClient;
import com.stocker.inventory.infrastructure.client.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Free-text pantry items: no catalog product needed; the catalog is only a best-effort name source. */
class CustomPantryItemServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final UUID userId = UUID.randomUUID();
    private InMemoryPantryItemRepository repository;
    private CatalogClient catalog;
    private PantryItemService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPantryItemRepository();
        catalog = mock(CatalogClient.class);
        service = new PantryItemService(repository, catalog);
    }

    private AddPantryItemCommand cmd(UUID productId, String name, int qty, LocalDate exp, Integer notify) {
        return new AddPantryItemCommand(userId, productId, name, "preserves", "jar", qty, exp, notify);
    }

    @Test
    void freeTextItemNeedsNoProductIdAndNoCatalog() {
        PantryItem saved = service.addPantryItem(cmd(null, "  Nana's jam ", 2, TODAY.plusDays(10), 5));

        assertThat(saved.getProduct_id()).isNull();
        assertThat(saved.getName()).isEqualTo("Nana's jam");
        assertThat(saved.getCategory()).isEqualTo("preserves");
        assertThat(saved.getUnit()).isEqualTo("jar");
        assertThat(saved.getExpirationDate()).isEqualTo(TODAY.plusDays(10));
        assertThat(saved.getNotifyDaysBefore()).isEqualTo(5);
        verifyNoInteractions(catalog);
    }

    @Test
    void catalogOutageOrNotFoundNeverBlocksCreation() {
        UUID down = UUID.randomUUID();
        when(catalog.findById(down)).thenThrow(new ResourceAccessException("catalog down"));
        PantryItem a = service.addPantryItem(new AddPantryItemCommand(userId, down, null, null, null, 1, null, null));
        assertThat(a.getProduct_id()).isEqualTo(down);
        assertThat(a.getName()).isNull();

        UUID missing = UUID.randomUUID(); // mock returns null = 404
        assertThat(service.addPantryItem(new AddPantryItemCommand(userId, missing, "Milk", null, null, 1, null, null))
                .getName()).isEqualTo("Milk");
    }

    @Test
    void catalogNameFillsInAndSameProductMergesKeepingName() {
        UUID p = UUID.randomUUID();
        when(catalog.findById(p)).thenReturn(new ProductResponse(p, null, "Anchor Milk", "2L"));
        service.addPantryItem(new AddPantryItemCommand(userId, p, null, null, null, 1, TODAY.plusDays(3), 2));
        PantryItem merged = service.addPantryItem(new AddPantryItemCommand(userId, p, null, null, null, 2, null, null));

        assertThat(merged.getQuantity()).isEqualTo(3);
        assertThat(merged.getName()).isEqualTo("Anchor Milk");
        assertThat(merged.getExpirationDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(service.getPantryItems(userId)).hasSize(1);
    }

    @Test
    void rejectsMissingNameAndProduct() {
        assertThatThrownBy(() -> service.addPantryItem(cmd(null, "   ", 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("productId or name");
        assertThatThrownBy(() -> service.addPantryItem(cmd(null, "x", 0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.addPantryItem(cmd(null, "x", 1, null, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customItemsSurviveQuantityAndExpiryUpdatesAndAreDueForNotification() {
        PantryItem jam = service.addPantryItem(cmd(null, "Jam", 1, TODAY.plusDays(2), 3));
        service.updatePantryItem(jam.getPantry_item_id(), 4);
        service.updateExpiry(jam.getPantry_item_id(), TODAY.plusDays(1), 3);

        assertThat(service.findDueForNotification(TODAY)).singleElement().satisfies(i -> {
            assertThat(i.getName()).isEqualTo("Jam");
            assertThat(i.getQuantity()).isEqualTo(4);
        });
    }
}
