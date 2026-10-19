package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test fake for the repository port: lets service-level tests run real services without a database. */
public class InMemoryPantryItemRepository implements PantryItemRepository {

    private final Map<UUID, PantryItem> items = new LinkedHashMap<>();

    @Override
    public PantryItem save(PantryItem item) {
        UUID id = item.getPantry_item_id() != null ? item.getPantry_item_id() : UUID.randomUUID();
        PantryItem stored = new PantryItem(id, item.getUser_id(), item.getProduct_id(), item.getName(),
                item.getCategory(), item.getUnit(), item.getQuantity(), item.getExpirationDate(),
                item.getNotifyDaysBefore());
        items.put(id, stored);
        return stored;
    }

    @Override
    public List<PantryItem> findByUserId(UUID userId) {
        return items.values().stream().filter(item -> item.getUser_id().equals(userId)).toList();
    }

    @Override
    public Optional<PantryItem> findByUserIdAndProductId(UUID userId, UUID productId) {
        return items.values().stream()
                .filter(item -> item.getUser_id().equals(userId) && productId.equals(item.getProduct_id()))
                .findFirst();
    }

    @Override
    public Optional<PantryItem> findById(UUID pantryItemId) {
        return Optional.ofNullable(items.get(pantryItemId));
    }

    @Override
    public List<PantryItem> findAllWithExpirationDate() {
        return items.values().stream().filter(item -> item.getExpirationDate() != null).toList();
    }

    @Override
    public void deleteById(UUID pantryItemId) {
        items.remove(pantryItemId);
    }
}
