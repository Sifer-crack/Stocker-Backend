package com.stocker.shopping.api.rest;

import com.stocker.shopping.application.ShoppingListService;
import com.stocker.shopping.infrastructure.persistence.ShoppingListItemEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/shopping-lists")
public class ShoppingListController {
    private final ShoppingListService shoppingListService;

    public ShoppingListController(ShoppingListService shoppingListService) {
        this.shoppingListService = shoppingListService;
    }

    @GetMapping
    public List<ShoppingListItemEntity> getItems(@RequestParam UUID userId) {
        return shoppingListService.getItems(userId);
    }

    @PostMapping("/items")
    public ShoppingListItemEntity addItem(
            @RequestParam UUID userId,
            @RequestParam UUID productId,
            @RequestParam(defaultValue = "1") int quantity) {
        try {
            return shoppingListService.addItem(userId, productId, quantity);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, exception.getMessage()
            );
        }
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(
            @RequestParam UUID userId,
            @PathVariable UUID itemId) {
        try {
            shoppingListService.removeItem(userId, itemId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    exception.getMessage()
            );
        }
    }
}
