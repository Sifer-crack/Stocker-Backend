package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pantry-items")
@CrossOrigin(origins = "http://localhost:5173") // TODO: domain for the frontend
public class PantryItemController {
    private final PantryItemService pantryItemService;


    public PantryItemController(PantryItemService pantryItemService) {
        this.pantryItemService = pantryItemService;
    }

    @PostMapping
    public PantryItem addPantryItem(
            @RequestParam UUID userId,
            @RequestParam UUID productId,
            @RequestParam int quantity) {

        return pantryItemService.addPantryItem(userId, productId, quantity);
    }

    @GetMapping
    public List<PantryItem> getPantry(@RequestParam UUID userId) {
        return pantryItemService.getPantryItems(userId);
    }
    @PutMapping("/{pantryItemId}")
    public PantryItem updatePantryItem(
            @PathVariable UUID pantryItemId,
            @RequestParam int quantity) {

        return pantryItemService.updatePantryItem(pantryItemId, quantity);
    }

    @DeleteMapping("/{pantryItemId}")
    public void deletePantryItem(@PathVariable UUID pantryItemId) {
        pantryItemService.deletePantryItem(pantryItemId);
    }
}
