package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;
import com.stocker.inventory.infrastructure.client.CatalogClient;
import com.stocker.inventory.infrastructure.client.ProductResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PantryItemService {
    private final PantryItemRepository pantryItemRepository;

    public PantryItemService(PantryItemRepository pantryItemRepository, CatalogClient catalogClient) {
        this.pantryItemRepository = pantryItemRepository;
    }

    public List<PantryItem> getPantryItems(UUID userId) {
        return pantryItemRepository.findByUserId(userId);
    }

    public PantryItem addPantryItem(UUID userId, UUID productId, int quantity) {
        Optional<PantryItem> existing = pantryItemRepository.findByUserIdAndProductId(userId, productId);

        if (existing.isPresent()) {
            PantryItem current = existing.get();

            PantryItem updated = new PantryItem(
                    current.getPantry_item_id(),
                    current.getUser_id(),
                    current.getProduct_id(),
                    current.getQuantity() + quantity // adds the incoming quantity added to the remaining quantity
            );

            return pantryItemRepository.save(updated);
        }

        return pantryItemRepository.save(new PantryItem(
                null,
                userId,
                productId,
                quantity
        ));
    }

    public void deletePantryItem(UUID pantryItemId) {
        pantryItemRepository.deleteById(pantryItemId);
    }
}