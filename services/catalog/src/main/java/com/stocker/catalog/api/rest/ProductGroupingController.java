package com.stocker.catalog.api.rest;

import com.stocker.catalog.application.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products/grouping")
@CrossOrigin(origins = "http://localhost:5173")
public class ProductGroupingController {
    private final ProductGroupingService groupingService;
    private final ProductImportService importService;


    public ProductGroupingController(
            ProductGroupingService groupingService, ProductImportService importService) {
        this.groupingService = groupingService;
        this.importService = importService;
    }

    @GetMapping("/preview")
    public List<ProductGroupPreview> previewGroups() {
        return groupingService.previewGroups();
    }

    @GetMapping("/preview/safe")
    public List<ProductGroupPreview> previewSafeGroups() {
        return groupingService.previewGroups().stream().filter(group -> !group.needsReview()).toList();
    }

    @GetMapping("/import-preview/")
    public List<ProductImportPreview> previewImport() {
        return groupingService.previewImport();
    }

    @PostMapping("/import")
    public ProductImportResult importProducts() {
        return importService.importProducts();
    }
}