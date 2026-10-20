package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.PantryItemService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Service-to-service endpoints. The gateway only routes /api/pantry-items/**, so nothing under /internal is
 * reachable from a browser: this list contains every user's pantry items.
 */
@RestController
@RequestMapping("/internal/pantry-items")
public class InternalPantryItemController {
    private final PantryItemService pantryItemService;

    public InternalPantryItemController(PantryItemService pantryItemService) {
        this.pantryItemService = pantryItemService;
    }

    /** Items whose notification window has opened on {@code asOf}; polled by the notifications service. */
    @GetMapping("/expiring")
    public List<ExpiringPantryItemResponse> getExpiring(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return pantryItemService.findDueForNotification(asOf).stream().map(ExpiringPantryItemResponse::from).toList();
    }
}
