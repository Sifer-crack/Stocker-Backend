package com.stocker.catalog.application;

import com.stocker.catalog.domain.ProductGroupKey;
import com.stocker.catalog.infrastructure.persistence.*;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ProductImportService {
    private final ProductGroupingService groupingService;
    private final SpringDataProductRepository productRepository;
    private final SpringDataProductItemMappingRepository mappingRepository;

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    public ProductImportService(
            ProductGroupingService groupingService,
            SpringDataProductRepository productRepository,
            SpringDataProductItemMappingRepository mappingRepository) {

        this.groupingService = groupingService;
        this.productRepository = productRepository;
        this.mappingRepository = mappingRepository;
    }

    @Transactional
    public ProductImportResult importProducts() {
        log.info("IMPORT: starting");

        if (productRepository.count() > 0 || mappingRepository.count() > 0) {
            throw new IllegalStateException("Product import can only run when products and mappings are empty.");
        }

        log.info("IMPORT: loading/grouping items");

        Map<ProductGroupKey, List<ItemEntity>> groups = groupingService.getAllGroups();

        List<ProductEntity> products = new ArrayList<>();
        List<List<ItemEntity>> productItems = new ArrayList<>();

        // build all safe products first
        for (Map.Entry<ProductGroupKey, List<ItemEntity>> entry : groups.entrySet()) {
            List<ItemEntity> items = entry.getValue();

            if (!groupingService.isSafeGroup(items)) {
                continue;
            }

            ProductGroupKey key = entry.getKey();

            ProductEntity product = new ProductEntity();

            product.setProductName(key.normalizedName());
            product.setGroceryType(key.groceryType());
            product.setSellingWeightKg(key.weightKg());
            product.setSellingVolumeL(key.volumeL());

            products.add(product);
            productItems.add(items);

        }

        log.info("IMPORT: built {} products", products.size());

        if (products.isEmpty()) {
            throw new IllegalStateException("Import produced no products");
        }

        log.info("IMPORT: saving products");

        // persist products so their uuids are generated
        List<ProductEntity> savedProducts = productRepository.saveAll(products);

        log.info("IMPORT: saveAll(products) returned");

        // build mappings using the generated product uuids
        List<ProductItemMappingEntity> mappings = new ArrayList<>();

        for (int i = 0; i < savedProducts.size(); i++) {
            ProductEntity product = savedProducts.get(i);
            List<ItemEntity> items = productItems.get(i);

            for (ItemEntity item : items) {
                mappings.add(new ProductItemMappingEntity(product.getProductId(), item.getId()));
            }
        }

        log.info("IMPORT: built {} mappings", mappings.size());

        if (mappings.isEmpty()) {
            throw new IllegalStateException("Import produced no product-item mappings.");
        }

        mappingRepository.saveAll(mappings);
        log.info("IMPORT: saveAll(mappings) returned");
        log.info("IMPORT: method complete");

        return new ProductImportResult(savedProducts.size(), mappings.size());
    }
}

