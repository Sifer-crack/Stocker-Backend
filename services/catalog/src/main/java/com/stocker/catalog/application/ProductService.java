package com.stocker.catalog.application;

import com.stocker.catalog.application.port.ProductRepository;
import com.stocker.catalog.domain.Product;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    // create product with a randomly generated unique product id
    public Product createProduct(String productName, String groceryType, BigDecimal sellingWeightKg, BigDecimal sellingVolumeL) {
        Product product = new Product(null, productName, groceryType, sellingWeightKg, sellingVolumeL);

        return productRepository.save(product);
    }

    public List<Product> getProducts() {
        return productRepository.findAll();
    }

    public List<Product> searchProducts(String query) {
        return productRepository.searchByName(query);
    }

    public Product getProduct(UUID productId) {
        return productRepository.findById(productId).orElseThrow(() -> new RuntimeException("Product not found"));
    }

    public void deleteProduct(UUID productId) {
        productRepository.deleteById(productId);
    }

}
