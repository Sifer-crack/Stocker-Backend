package com.stocker.inventory.application.port;

import com.stocker.inventory.domain.PantryItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PantryItemRepository {
    PantryItem save(PantryItem pantryItem);
    List<PantryItem> findByUserId(UUID userId);
    Optional<PantryItem> findByUserIdAndProductId(UUID userId, UUID productId);
    Optional<PantryItem> findById(UUID pantryItemId);
    /** The item only if it belongs to {@code userId}; a foreign or unknown id is empty. */
    Optional<PantryItem> findByIdAndUserId(UUID pantryItemId, UUID userId);
    List<PantryItem> findAllWithExpirationDate();
    void deleteById(UUID pantryItemId);
}
