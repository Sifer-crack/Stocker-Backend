package com.stocker.pantry.infrastructure.persistence;

import com.stocker.pantry.domain.PantryItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataPantryItemRepository extends JpaRepository<PantryItemEntity, UUID> {
    PantryItem save(PantryItem pantryItem);
    List<PantryItemEntity> findByUserId(UUID userId);
}
