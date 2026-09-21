package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PantryItemService {
    private final PantryItemRepository pantryItemRepository;

    public PantryItemService(PantryItemRepository pantryItemRepository) {
        this.pantryItemRepository = pantryItemRepository;
    }

    public List<PantryItem> getPantryItems(UUID userId) {
        return pantryItemRepository.findByUserId(userId);
    }

    public PantryItem addPantryItem(UUID userId, UUID productId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }

        Optional<PantryItem> existing = pantryItemRepository.findByUserIdAndProductId(userId, productId);

        // if the product already exists inside the users pantry, just add to it rather than making a new row
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

        // otherwise if the product isn't inside the user's pantry then add a new row for the product in pantry
        return pantryItemRepository.save(new PantryItem(
                null,
                userId,
                productId,
                quantity
        ));
    }
    public PantryItem updatePantryItem(UUID pantryItemId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }

        PantryItem current = pantryItemRepository.findById(pantryItemId)
                .orElseThrow(() -> new IllegalArgumentException("Pantry item not found"));

        PantryItem updated = new PantryItem(
                current.getPantry_item_id(),
                current.getUser_id(),
                current.getProduct_id(),
                quantity
        );
        return pantryItemRepository.save(updated);
    }
    public void deletePantryItem(UUID pantryItemId) {
        pantryItemRepository.deleteById(pantryItemId);
    }
}