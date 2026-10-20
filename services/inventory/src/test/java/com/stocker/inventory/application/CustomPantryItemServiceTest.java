package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;
import com.stocker.inventory.infrastructure.client.CatalogClient;
import com.stocker.inventory.infrastructure.client.ProductResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.client.ResourceAccessException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
        service.updatePantryItem(userId, jam.getPantry_item_id(), 4);
        service.updateExpiry(userId, jam.getPantry_item_id(), TODAY.plusDays(1), 3);

        assertThat(service.findDueForNotification(TODAY)).singleElement().satisfies(i -> {
            assertThat(i.getName()).isEqualTo("Jam");
            assertThat(i.getQuantity()).isEqualTo(4);
        });
    }

    @Test
    void rejectsOversizedFieldsAndQuantities() {
        assertThatThrownBy(() -> service.addPantryItem(cmd(null, "n".repeat(256), 1, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("255");
        assertThatThrownBy(() -> service.addPantryItem(new AddPantryItemCommand(userId, null, "x", "c".repeat(101),
                null, 1, null, null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> service.addPantryItem(new AddPantryItemCommand(userId, null, "x", null,
                "u".repeat(51), 1, null, null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50");
        assertThatThrownBy(() -> service.addPantryItem(cmd(null, "x", 100_001, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100000");
        PantryItem ok = service.addPantryItem(cmd(null, "n".repeat(255), 100_000, null, null));
        assertThatThrownBy(() -> service.updatePantryItem(userId, ok.getPantry_item_id(), 100_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergingPastTheMaximumQuantityIsRejected() {
        UUID p = UUID.randomUUID();
        service.addPantryItem(new AddPantryItemCommand(userId, p, "Rice", null, null, 99_999, null, null));
        assertThatThrownBy(() -> service.addPantryItem(new AddPantryItemCommand(userId, p, null, null, null, 2,
                null, null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100000");
    }

    @Test
    void missingOrForeignItemsAreNotFound() {
        PantryItem mine = service.addPantryItem(cmd(null, "Jam", 1, null, null));
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> service.updatePantryItem(other, mine.getPantry_item_id(), 2))
                .isInstanceOf(PantryItemNotFoundException.class);
        assertThatThrownBy(() -> service.updateExpiry(other, mine.getPantry_item_id(), TODAY, 1))
                .isInstanceOf(PantryItemNotFoundException.class);
        assertThatThrownBy(() -> service.deletePantryItem(other, mine.getPantry_item_id()))
                .isInstanceOf(PantryItemNotFoundException.class);
        assertThatThrownBy(() -> service.deletePantryItem(userId, UUID.randomUUID()))
                .isInstanceOf(PantryItemNotFoundException.class);
        assertThat(service.getPantryItems(userId)).hasSize(1);
        service.deletePantryItem(userId, mine.getPantry_item_id());
        assertThat(service.getPantryItems(userId)).isEmpty();
    }

    @Test
    void lostMergeRaceIsRetriedAsAMerge() {
        UUID p = UUID.randomUUID();
        PantryItem winner = new PantryItem(UUID.randomUUID(), userId, p, "Milk", null, null, 1, null, null);
        PantryItemRepository repo = mock(PantryItemRepository.class);
        when(repo.findByUserIdAndProductId(userId, p)).thenReturn(Optional.empty(), Optional.of(winner));
        when(repo.save(any())).thenAnswer(inv -> {
            PantryItem i = inv.getArgument(0);
            if (i.getPantry_item_id() == null) {
                throw new DataIntegrityViolationException("uq_pantry_user_product");
            }
            return i;
        });

        PantryItem result = new PantryItemService(repo).addPantryItem(
                new AddPantryItemCommand(userId, p, "Milk", null, null, 2, null, null));

        assertThat(result.getPantry_item_id()).isEqualTo(winner.getPantry_item_id());
        assertThat(result.getQuantity()).isEqualTo(3);
    }

    @Test
    void aRaceThatCannotBeResolvedStillSurfacesAsADataIntegrityViolation() {
        PantryItemRepository repo = mock(PantryItemRepository.class);
        when(repo.findByUserIdAndProductId(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenThrow(new DataIntegrityViolationException("boom"));
        UUID p = UUID.randomUUID();
        assertThatThrownBy(() -> new PantryItemService(repo).addPantryItem(
                new AddPantryItemCommand(userId, p, "Milk", null, null, 1, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
