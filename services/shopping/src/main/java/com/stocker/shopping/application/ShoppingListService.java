package com.stocker.shopping.application;

import com.stocker.shopping.infrastructure.persistence.ShoppingListEntity;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemEntity;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemRepository;
import com.stocker.shopping.infrastructure.persistence.ShoppingListRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ShoppingListService {
    private final ShoppingListRepository listRepository;
    private final ShoppingListItemRepository itemRepository;

    public ShoppingListService(
            ShoppingListRepository listRepository,
            ShoppingListItemRepository itemRepository) {
        this.listRepository = listRepository;
        this.itemRepository = itemRepository;
    }

    private ShoppingListEntity getOrCreateList(UUID userId) {
        return listRepository
                .findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, "ACTIVE")
                .orElseGet(() -> listRepository.save(
                        new ShoppingListEntity(userId, "Shopping List")
                ));
    }

    @Transactional(readOnly = true)
    public List<ShoppingListItemEntity> getItems(UUID userId) {
        ShoppingListEntity list = getOrCreateList(userId);

        return itemRepository.findByShoppingListId(list.getShoppingListId());
    }

    @Transactional
    public ShoppingListItemEntity addItem(
            UUID userId,
            UUID productId,
            int quantity) {

        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1.");
        }
        ShoppingListEntity list = getOrCreateList(userId);

        return itemRepository
                .findByShoppingListIdAndProductId(list.getShoppingListId(), productId)
                .map(existing -> {
                    existing.setQuantity(existing.getQuantity() + quantity);

                    return itemRepository.save(existing);
                }).orElseGet(() -> itemRepository.save(
                        new ShoppingListItemEntity(list.getShoppingListId(), productId, quantity)
                ));
    }

    @Transactional
    public void removeItem(UUID userId, UUID itemId) {
        ShoppingListEntity list = getOrCreateList(userId);

        ShoppingListItemEntity item = itemRepository
                .findByShoppingListItemIdAndShoppingListId(
                        itemId, list.getShoppingListId())
                .orElseThrow(() ->
                        new IllegalArgumentException("Shopping List item not found."));
        itemRepository.delete(item);
    }
}
