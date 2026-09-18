package com.stocker.inventory.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPantryItemRepository extends JpaRepository<PantryItemEntity, UUID> {
    List<PantryItemEntity> findByUserId(UUID userId);

    Optional<PantryItemEntity> findByUserIdAndProductId(UUID userId, UUID productId);
}
