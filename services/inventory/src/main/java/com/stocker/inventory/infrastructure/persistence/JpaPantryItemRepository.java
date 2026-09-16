package com.stocker.inventory.infrastructure.persistence;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaPantryItemRepository implements PantryItemRepository {
    private final SpringDataPantryItemRepository repository;

    public JpaPantryItemRepository(SpringDataPantryItemRepository repository) {
        this.repository = repository;
    }

    @Override
    public PantryItem save(PantryItem pantryItem) {
        PantryItemEntity entity = new PantryItemEntity();

        if (pantryItem.getPantry_item_id() != null) {
            entity = repository.findById(pantryItem.getPantry_item_id()).orElseThrow(() -> new RuntimeException("Pantry Item Not Found"));
        }
        else {
            entity = new PantryItemEntity();
            entity.setUserId(pantryItem.getUser_id());
            entity.setProductId(pantryItem.getProduct_id());
        }

        entity.setQuantityRemaining(pantryItem.getQuantity());

        PantryItemEntity saved = repository.save(entity);

        return new PantryItem(
                saved.getPantryItemId(),
                saved.getUserId(),
                saved.getProductId(),
                saved.getQuantity()
        );
    }

    @Override
    public List<PantryItem> findByUserId(UUID userId) {
        return repository.findByUserId(userId).stream().map(entity -> new PantryItem(
                entity.getPantryItemId(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getQuantity()
        )).toList();
    }

    @Override
    public Optional<PantryItem> findByUserIdAndProductId(UUID userId, UUID productId) {
        return repository.findByUserIdAndProductId(userId, productId).map(entity -> new PantryItem(
                entity.getPantryItemId(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getQuantity()
        ));
    }

    @Override
    public Optional<PantryItem> findById(UUID pantryItemId) {
        return repository.findById(pantryItemId).map(entity -> new PantryItem(
                entity.getPantryItemId(),
                entity.getUserId(),
                entity.getProductId(),
                entity.getQuantity()
        ));
    }

    @Override
    public void deleteById(UUID pantryItemId) {
        repository.deleteById(pantryItemId);
    }
}
