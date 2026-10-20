package com.stocker.shopping.application;

import com.stocker.shopping.domain.ShoppingListItem;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShoppingListService {
    private final ShoppingListItemRepository itemRepository;
    private final Clock clock;

    public ShoppingListService(ShoppingListItemRepository itemRepository, Clock clock) {
        this.itemRepository = itemRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ShoppingListItem> getItems(UUID userId) {
        return itemRepository.findByUserIdOrderByCreatedAtDesc(userId.toString());
    }

    @Transactional
    public ShoppingListItem addItem(
            UUID userId,
            UUID productId,
            int quantity) {

        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1.");
        }
        String sku = productId.toString();

        return itemRepository
                .findByUserIdAndSku(userId.toString(), sku)
                .map(existing -> {
                    existing.addQuantity(quantity, now());

                    return itemRepository.save(existing);
                }).orElseGet(() -> itemRepository.save(
                        ShoppingListItem.create(
                                userId.toString(),
                                null,
                                sku,
                                sku,
                                null,
                                null,
                                quantity,
                                now())
                ));
    }

    @Transactional
    public void removeItem(UUID userId, UUID itemId) {
        ShoppingListItem item = itemRepository.findById(itemId)
                .filter(existing -> existing.getUserId().equals(userId.toString()))
                .orElseThrow(() ->
                        new IllegalArgumentException("Shopping List item not found."));
        itemRepository.delete(item);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
