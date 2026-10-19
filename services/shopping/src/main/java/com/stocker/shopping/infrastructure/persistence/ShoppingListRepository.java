package com.stocker.shopping.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShoppingListRepository extends JpaRepository<ShoppingListEntity, UUID> {
    Optional<ShoppingListEntity> findFirstByUserIdAndStatusOrderByCreatedAtDesc(
            UUID userId,
            String status
    );
}
