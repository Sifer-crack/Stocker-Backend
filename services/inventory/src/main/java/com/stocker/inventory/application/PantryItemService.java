package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;

import com.stocker.inventory.infrastructure.client.CatalogClient;
import com.stocker.inventory.infrastructure.client.ProductResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PantryItemService {
    private static final Logger log = LoggerFactory.getLogger(PantryItemService.class);

    static final int MAX_NAME = 255;
    static final int MAX_CATEGORY = 100;
    static final int MAX_UNIT = 50;
    static final int MAX_QUANTITY = 100_000;

    private final PantryItemRepository pantryItemRepository;
    private final CatalogClient catalogClient;

    /** Without a catalog client no name lookup happens (tests). */
    public PantryItemService(PantryItemRepository pantryItemRepository) {
        this(pantryItemRepository, null);
    }

    @Autowired
    public PantryItemService(PantryItemRepository pantryItemRepository, CatalogClient catalogClient) {
        this.pantryItemRepository = pantryItemRepository;
        this.catalogClient = catalogClient;
    }

    public List<PantryItem> getPantryItems(UUID userId) {
        return pantryItemRepository.findByUserId(userId);
    }

    public PantryItem addPantryItem(UUID userId, UUID productId, int quantity) {
        return addPantryItem(userId, productId, quantity, null, null);
    }

    public PantryItem addPantryItem(UUID userId, UUID productId, int quantity,
                                    LocalDate expirationDate, Integer notifyDaysBefore) {
        return addPantryItem(new AddPantryItemCommand(userId, productId, null, null, null, quantity,
                expirationDate, notifyDaysBefore));
    }

    /** Adds a catalog product (productId) and/or a free-text item (name); at least one of the two is required. */
    public PantryItem addPantryItem(AddPantryItemCommand cmd) {
        validateQuantity(cmd.quantity());
        validateExpiry(cmd.expirationDate(), cmd.notifyDaysBefore());
        String name = blankToNull(cmd.name());
        validateLength("Name", name, MAX_NAME);
        validateLength("Category", blankToNull(cmd.category()), MAX_CATEGORY);
        validateLength("Unit", blankToNull(cmd.unit()), MAX_UNIT);
        if (cmd.productId() == null && name == null) {
            throw new IllegalArgumentException("Either productId or name is required");
        }

        if (cmd.productId() != null) {
            Optional<PantryItem> existing = pantryItemRepository.findByUserIdAndProductId(cmd.userId(), cmd.productId());

            // if the product already exists inside the users pantry, just add to it rather than making a new row
            if (existing.isPresent()) {
                return merge(existing.get(), cmd);
            }
            if (name == null) {
                name = lookupCatalogName(cmd.productId());
            }
        }

        PantryItem fresh = new PantryItem(null, cmd.userId(), cmd.productId(), name,
                blankToNull(cmd.category()), blankToNull(cmd.unit()), cmd.quantity(),
                cmd.expirationDate(), cmd.notifyDaysBefore());
        try {
            return pantryItemRepository.save(fresh);
        } catch (DataIntegrityViolationException e) {
            // two concurrent adds of the same product: the other one won, so merge into its row instead
            if (cmd.productId() != null) {
                Optional<PantryItem> winner = pantryItemRepository.findByUserIdAndProductId(cmd.userId(), cmd.productId());
                if (winner.isPresent()) {
                    return merge(winner.get(), cmd);
                }
            }
            throw e;
        }
    }

    private PantryItem merge(PantryItem current, AddPantryItemCommand cmd) {
        int total = current.getQuantity() + cmd.quantity();
        validateQuantity(total);
        // a new expiry replaces the old one; no expiry given keeps whatever was there
        PantryItem merged = cmd.expirationDate() != null
                ? current.withExpiry(cmd.expirationDate(), cmd.notifyDaysBefore()) : current;
        return pantryItemRepository.save(merged.withQuantity(total));
    }

    /** The catalog is only a best-effort name source: an outage or a 404 must never block adding an item. */
    private String lookupCatalogName(UUID productId) {
        if (catalogClient == null) {
            return null;
        }
        try {
            ProductResponse product = catalogClient.findById(productId);
            return product == null ? null : blankToNull(product.name());
        } catch (RuntimeException e) {
            log.warn("Catalog lookup failed for product {}: {}", productId, e.toString());
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public PantryItem updatePantryItem(UUID userId, UUID pantryItemId, int quantity) {
        validateQuantity(quantity);

        PantryItem current = findOrThrow(userId, pantryItemId);
        return pantryItemRepository.save(current.withQuantity(quantity));
    }

    /** Sets (or, with a null date, clears) the expiration date and notification threshold of one item. */
    public PantryItem updateExpiry(UUID userId, UUID pantryItemId, LocalDate expirationDate, Integer notifyDaysBefore) {
        validateExpiry(expirationDate, notifyDaysBefore);
        PantryItem current = findOrThrow(userId, pantryItemId);
        return pantryItemRepository.save(current.withExpiry(expirationDate, notifyDaysBefore));
    }

    /** Items whose notification window has opened on {@code today}; the notifications service polls this. */
    public List<PantryItem> findDueForNotification(LocalDate today) {
        return pantryItemRepository.findAllWithExpirationDate().stream()
                .filter(item -> item.isNotificationDue(today))
                .toList();
    }

    public void deletePantryItem(UUID userId, UUID pantryItemId) {
        PantryItem current = findOrThrow(userId, pantryItemId);
        pantryItemRepository.deleteById(current.getPantry_item_id());
    }

    private PantryItem findOrThrow(UUID userId, UUID pantryItemId) {
        return pantryItemRepository.findByIdAndUserId(pantryItemId, userId)
                .orElseThrow(PantryItemNotFoundException::new);
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        if (quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Quantity cannot exceed " + MAX_QUANTITY);
        }
    }

    private static void validateLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters");
        }
    }

    private static void validateExpiry(LocalDate expirationDate, Integer notifyDaysBefore) {
        if (notifyDaysBefore != null && notifyDaysBefore < 0) {
            throw new IllegalArgumentException("Notification threshold cannot be negative");
        }
        if (notifyDaysBefore != null && expirationDate == null) {
            throw new IllegalArgumentException("A notification threshold needs an expiration date");
        }
    }
}
