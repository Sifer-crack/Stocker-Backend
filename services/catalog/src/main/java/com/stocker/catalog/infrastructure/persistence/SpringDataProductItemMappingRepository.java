package com.stocker.catalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataProductItemMappingRepository extends JpaRepository<ProductItemMappingEntity, ProductItemMappingId> {
    Optional<ProductItemMappingEntity> findByIdItemId(Integer itemId);
    boolean existsByIdItemId(Integer itemId);
    List<ProductItemMappingEntity> findByIdItemIdIn(Collection<Integer> itemIds); // SQL example: WHERE item_id IN (85, 26, 91)
}