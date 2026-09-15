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
    private final CatalogClient catalogClient;

    public PantryItemService(PantryItemRepository pantryItemRepository, CatalogClient catalogClient) {
        this.pantryItemRepository = pantryItemRepository;
        this.catalogClient = catalogClient;
    }

    public List<PantryItem> getPantryItems(UUID userId) {
        return pantryItemRepository.findByUserId(userId);
    }

    public PantryItem addPantryItem(UUID userId, String productName, String unit, int quantity) {
        ProductResponse product = catalogClient.findByNameAndUnit(productName, unit);

        if (product == null) {
            product = catalogClient.createProduct(productName, unit);
        }

        Optional<PantryItem> existing = pantryItemRepository.findByUserIdAndProductId(userId, product.productId());

        if (existing.isPresent()) {
            PantryItem current = existing.get();
            System.out.println("Current quantity: " + current.getQuantity());
            System.out.println("Adding quantity " + quantity);
            PantryItem updated = new PantryItem(
                    current.getPantry_item_id(),
                    current.getUser_id(),
                    current.getProduct_id(),
                    current.getQuantity() + quantity // adds the incoming quantity added to the remaining quantity
            );

            return pantryItemRepository.save(updated);
        }

        // TODO: Once auth system is running, change so that the user id maps to proper user account
        PantryItem newItem = new PantryItem(null, userId, product.productId(), quantity);

        return pantryItemRepository.save(newItem);
    }

    public void deletePantryItem(UUID pantryItemId) {
        pantryItemRepository.deleteById(pantryItemId);
    }
}