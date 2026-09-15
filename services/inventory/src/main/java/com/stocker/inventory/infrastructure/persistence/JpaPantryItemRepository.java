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

        entity.setUserId(pantryItem.getUser_id());
        entity.setProductId(pantryItem.getProduct_id());
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
    public Optional<PantryItem> findById(UUID pantryItemId) {
        return Optional.empty();
    }

    @Override
    public void deleteById(UUID pantryItemId) {

    }
}
