package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pantry-items")
public class PantryItemController {
    private final PantryItemService pantryItemService;


    public PantryItemController(PantryItemService pantryItemService) {
        this.pantryItemService = pantryItemService;
    }

    @PostMapping
    public PantryItem addPantryItem(
            @RequestParam UUID userId,
            @RequestParam String productName,
            @RequestParam String unit,
            @RequestParam int quantity) {

        return pantryItemService.addPantryItem(userId, productName, unit, quantity);
    }

    @GetMapping
    public List<PantryItem> getPantry(@RequestParam UUID userId) {
        return pantryItemService.getPantryItems(userId);
    }
}
