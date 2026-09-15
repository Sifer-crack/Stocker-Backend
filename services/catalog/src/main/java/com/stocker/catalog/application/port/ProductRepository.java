package com.stocker.catalog.application.port;

import com.stocker.catalog.domain.Product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository {
    List<Product> findAll();

    Optional<Product> findById(UUID id);

    Product save(Product product);

    void deleteById(UUID productId);

    Optional<Product> findByNameAndUnit(String name, String unit);
}
