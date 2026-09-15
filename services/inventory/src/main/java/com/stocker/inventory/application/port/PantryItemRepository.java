package com.stocker.inventory.application.port;

import com.stocker.inventory.domain.PantryItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PantryItemRepository {
    PantryItem save(PantryItem pantryItem);
    List<PantryItem> findByUserId(UUID userId);
    Optional<PantryItem> findById(UUID pantryItemId);
    void deleteById(UUID pantryItemId);
}
