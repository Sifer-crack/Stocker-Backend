package com.stocker.catalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProductRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findByProductNameContainingIgnoreCase(String productName);
}
