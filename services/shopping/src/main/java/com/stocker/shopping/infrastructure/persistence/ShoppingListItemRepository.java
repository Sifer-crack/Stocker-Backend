package com.stocker.shopping.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItemEntity, UUID> {
    List<ShoppingListItemEntity> findByShoppingListId(UUID shoppingListId);

    Optional<ShoppingListItemEntity> findByShoppingListIdAndProductId(
            UUID shoppingListId,
            UUID productId
    );

    Optional<ShoppingListItemEntity> findByShoppingListItemIdAndShoppingListId(
            UUID itemId,
            UUID shoppingListId
    );
}

