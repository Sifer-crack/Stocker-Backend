package com.stocker.catalog.api.rest;

import com.stocker.catalog.application.ProductGroupPreview;
import com.stocker.catalog.application.ProductGroupingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products/grouping")
@CrossOrigin(origins = "http://localhost:5173")
public class ProductGroupingController {
    private final ProductGroupingService groupingService;

    public ProductGroupingController(
            ProductGroupingService groupingService) {
        this.groupingService = groupingService;
    }

    @GetMapping("/preview")
    public List<ProductGroupPreview> previewGroups() {
        return groupingService.previewGroups();
    }

    @GetMapping("/preview/safe")
    public List<ProductGroupPreview> previewSafeGroups() {
        return groupingService.previewGroups().stream().filter(group -> !group.needsReview()).toList();
    }
}