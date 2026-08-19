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
                .map(entity -> new Product(
                        entity.getProductId(),
                        entity.getCategoryId(),
                        entity.getName(),
                        entity.getUnit()
                ));
    }

    @Override
    public List<Product> findAll() {
        return repository.findAll().stream().map(entity -> new Product(
                entity.getProductId(),
                entity.getCategoryId(),
                entity.getName(),
                entity.getUnit()
        )).toList();

    }

    @Override
    public Product save(Product product) {
        ProductEntity entity = new ProductEntity();

        entity.setProductId(product.getProductId());
        entity.setCategoryId(product.getCategoryId());
        entity.setName(product.getName());
        entity.setUnit(product.getUnit());

        ProductEntity saved = repository.save(entity);

        return new Product(
                saved.getProductId(),
                saved.getCategoryId(),
                saved.getName(),
                saved.getUnit()
        );
    }

    @Override
    public void deleteById(UUID productId) {
        repository.deleteById(productId);
    }
}
