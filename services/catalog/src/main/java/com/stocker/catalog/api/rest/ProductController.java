package com.stocker.catalog.api.rest;

import com.stocker.catalog.application.ProductService;
import com.stocker.catalog.domain.Product;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/products")
@CrossOrigin(origins = "http://localhost:5173") // TODO: domain for the frontend

public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<Product> getProducts() {
        return productService.getProducts();
    }

    @GetMapping("/{id}")
    public Product getProduct(@PathVariable UUID id) {
        return productService.getProduct(id);
    }

    @PostMapping
    public Product createProduct(@RequestParam(required = false) UUID categoryId,
                                 @RequestParam String name,
                                 @RequestParam String unit) {

        return productService.createProduct(categoryId, name, unit);
    }

    // search from available products in products database
    @GetMapping("/search")
    public List<Product> searchProducts(
            @RequestParam String query) {
        return productService.searchProducts(query);
    }

    @DeleteMapping("/{id}")
    public void deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
    }

}