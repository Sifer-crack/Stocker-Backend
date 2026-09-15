package com.stocker.inventory.infrastructure.persistence;

import com.stocker.inventory.domain.PantryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataPantryItemRepository extends JpaRepository<PantryItemEntity, UUID> {
    PantryItem save(PantryItem pantryItem);
    List<PantryItemEntity> findByUserId(UUID userId);
}
