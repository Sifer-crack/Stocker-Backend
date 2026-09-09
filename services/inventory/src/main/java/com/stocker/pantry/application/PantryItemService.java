package com.stocker.pantry.application;

import com.stocker.pantry.application.port.PantryItemRepository;
import com.stocker.pantry.domain.PantryItem;
import org.springframework.stereotype.Service;

import java.util.List;
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
        // TODO: Once auth system is running, change so that the user id maps to proper user account
        PantryItem pantryItem = new PantryItem(null, userId, productId, quantity);

        return pantryItemRepository.save(pantryItem);
    }

    public void deletePantryItem(UUID pantryItemId) {
        pantryItemRepository.deleteById(pantryItemId);
    }
}