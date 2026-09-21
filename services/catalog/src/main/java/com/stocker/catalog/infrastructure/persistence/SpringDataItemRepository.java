package com.stocker.catalog.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataItemRepository extends JpaRepository<ItemEntity, Integer> {
}
