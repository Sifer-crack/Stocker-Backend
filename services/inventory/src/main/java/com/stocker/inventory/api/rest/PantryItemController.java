package com.stocker.inventory.api.rest;

import com.stocker.inventory.application.AddPantryItemCommand;
import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/pantry-items")
@CrossOrigin(origins = "http://localhost:5173") // TODO: domain for the frontend'
public class PantryItemController {
    private final PantryItemService pantryItemService;


    public PantryItemController(PantryItemService pantryItemService) {
        this.pantryItemService = pantryItemService;
    }

    private static UUID requireUser(String header) {
        if (header == null || header.isBlank()) {
            throw new UnauthorizedException("Missing X-User-Id header");
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid X-User-Id header");
        }
    }

    /**
     * JSON body alternative to the query parameters; every field is optional here, the service validates.
     * {@code userId} is still accepted so older frontends keep working, but it is ignored: the caller is the
     * user in the gateway-verified X-User-Id header.
     */
    public record AddPantryItemRequest(UUID userId, UUID productId, String name, String category, String unit,
                                       Integer quantity, LocalDate expirationDate, Integer notifyDaysBefore) {
    }

    /**
     * Adds an item from query parameters (the original contract) and/or a JSON body (body values win).
     * Either productId (catalog product) or name (free text) is required; the catalog is never required.
     */
    @PostMapping
    public PantryItem addPantryItem(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestBody(required = false) AddPantryItemRequest body,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String unit,
            @RequestParam(required = false) Integer quantity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required = false) Integer notifyDaysBefore) {

        AddPantryItemRequest b = body != null ? body
                : new AddPantryItemRequest(null, null, null, null, null, null, null, null);
        UUID user = requireUser(userHeader);
        if (quantity == null && b.quantity() == null) {
            throw new IllegalArgumentException("quantity is required");
        }
        if (body == null && productId != null && name == null && category == null && unit == null) {
            // the original contract: catalog product only
            return pantryItemService.addPantryItem(user, productId, quantity, expirationDate, notifyDaysBefore);
        }
        AddPantryItemCommand cmd = new AddPantryItemCommand(user, pick(b.productId(), productId),
                pick(b.name(), name), pick(b.category(), category), pick(b.unit(), unit),
                pick(b.quantity(), quantity), pick(b.expirationDate(), expirationDate),
                pick(b.notifyDaysBefore(), notifyDaysBefore));
        return pantryItemService.addPantryItem(cmd);
    }

    private static <T> T pick(T fromBody, T fromParam) {
        return fromBody != null ? fromBody : fromParam;
    }

    @GetMapping
    public List<PantryItem> getPantry(@RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        return pantryItemService.getPantryItems(requireUser(userHeader));
    }

    @PutMapping("/{pantryItemId}")
    public PantryItem updatePantryItem(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @PathVariable UUID pantryItemId,
            @RequestParam int quantity) {

        return pantryItemService.updatePantryItem(requireUser(userHeader), pantryItemId, quantity);
    }

    @PutMapping("/{pantryItemId}/expiry")
    public PantryItem updateExpiry(
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @PathVariable UUID pantryItemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expirationDate,
            @RequestParam(required = false) Integer notifyDaysBefore) {

        return pantryItemService.updateExpiry(requireUser(userHeader), pantryItemId, expirationDate, notifyDaysBefore);
    }

    @DeleteMapping("/{pantryItemId}")
    public void deletePantryItem(@RequestHeader(value = "X-User-Id", required = false) String userHeader,
                                 @PathVariable UUID pantryItemId) {
        pantryItemService.deletePantryItem(requireUser(userHeader), pantryItemId);
    }

    @PutMapping("/import")
    public List<PantryItem> replacePantry(
            @RequestParam UUID userId,
            @RequestBody List<ImportPantryItemRequest> importedItems
    ) {
        List<PantryItem> pantryItems = importedItems.stream()
                .map(item -> new PantryItem(
                        null,
                        userId,
                        item.product_id(),
                        item.quantity()
                ))
                .toList();

        return pantryItemService.replacePantry(userId, pantryItems);

    }
}
