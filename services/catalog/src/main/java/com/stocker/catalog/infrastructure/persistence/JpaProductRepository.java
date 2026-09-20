package com.stocker.catalog.infrastructure.persistence;

import com.stocker.catalog.application.port.ProductRepository;
import com.stocker.catalog.domain.Product;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaProductRepository implements ProductRepository {
    private final SpringDataProductRepository repository;

    public JpaProductRepository(SpringDataProductRepository repository) {

        this.repository = repository;
    }

    @Override
    public Optional<Product> findById(UUID productId) {
        return repository.findById(productId)
                .map(this::toDomain);
    }

    @Override
    public List<Product> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();

    }

    @Override
    public Product save(Product product) {
        ProductEntity entity = new ProductEntity();

        entity.setProductId(product.getProductId());
        entity.setProductName(product.getProductName());
        entity.setGroceryType(product.getGroceryType());
        entity.setSellingWeightKg(product.getSellingWeightKg());
        entity.setSellingVolumeL(product.getSellingVolumeL());

        ProductEntity saved = repository.save(entity);

        return toDomain(saved);

    }

    @Override
    public List<Product> searchByName(String query) {
        return repository
                .findByProductNameContainingIgnoreCase(query)
                .stream()
                .map(this::toDomain).toList();
    }

    @Override
    public void deleteById(UUID productId) {
        repository.deleteById(productId);
    }

    private Product toDomain(ProductEntity entity) {
        return new Product(
                entity.getProductId(),
                entity.getProductName(),
                entity.getGroceryType(),
                entity.getSellingWeightKg(),
                entity.getSellingVolumeL());
    }
}
