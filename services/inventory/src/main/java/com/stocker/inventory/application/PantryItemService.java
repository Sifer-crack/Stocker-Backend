package com.stocker.inventory.application;

import com.stocker.inventory.application.port.PantryItemRepository;
import com.stocker.inventory.domain.PantryItem;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PantryItemService {
    private final PantryItemRepository pantryItemRepository;

    public PantryItemService(PantryItemRepository pantryItemRepository) {
        this.pantryItemRepository = pantryItemRepository;
    }

    public List<PantryItem> getPantryItems(UUID userId) {
        return pantryItemRepository.findByUserId(userId);
    }

    public PantryItem addPantryItem(UUID userId, UUID productId, int quantity) {
        return addPantryItem(userId, productId, quantity, null, null);
    }

    public PantryItem addPantryItem(UUID userId, UUID productId, int quantity,
                                    LocalDate expirationDate, Integer notifyDaysBefore) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }
        validateExpiry(expirationDate, notifyDaysBefore);

        Optional<PantryItem> existing = pantryItemRepository.findByUserIdAndProductId(userId, productId);

        // if the product already exists inside the users pantry, just add to it rather than making a new row
        if (existing.isPresent()) {
            PantryItem current = existing.get();

            // a new expiry replaces the old one; no expiry given keeps whatever was there
            LocalDate expiry = expirationDate != null ? expirationDate : current.getExpirationDate();
            Integer threshold = expirationDate != null ? notifyDaysBefore : current.getNotifyDaysBefore();

            PantryItem updated = new PantryItem(
                    current.getPantry_item_id(),
                    current.getUser_id(),
                    current.getProduct_id(),
                    current.getQuantity() + quantity, // adds the incoming quantity added to the remaining quantity
                    expiry,
                    threshold
            );

            return pantryItemRepository.save(updated);
        }

        // otherwise if the product isn't inside the user's pantry then add a new row for the product in pantry
        return pantryItemRepository.save(new PantryItem(
                null,
                userId,
                productId,
                quantity,
                expirationDate,
                notifyDaysBefore
        ));
    }

    public PantryItem updatePantryItem(UUID pantryItemId, int quantity) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Quantity must be at least 1");
        }

        PantryItem current = findOrThrow(pantryItemId);
        return pantryItemRepository.save(current.withQuantity(quantity));
    }

    /** Sets (or, with a null date, clears) the expiration date and notification threshold of one item. */
    public PantryItem updateExpiry(UUID pantryItemId, LocalDate expirationDate, Integer notifyDaysBefore) {
        validateExpiry(expirationDate, notifyDaysBefore);
        PantryItem current = findOrThrow(pantryItemId);
        return pantryItemRepository.save(current.withExpiry(expirationDate, notifyDaysBefore));
    }

    /** Items whose notification window has opened on {@code today}; the notifications service polls this. */
    public List<PantryItem> findDueForNotification(LocalDate today) {
        return pantryItemRepository.findAllWithExpirationDate().stream()
                .filter(item -> item.isNotificationDue(today))
                .toList();
    }

    public void deletePantryItem(UUID pantryItemId) {
        pantryItemRepository.deleteById(pantryItemId);
    }

    private PantryItem findOrThrow(UUID pantryItemId) {
        return pantryItemRepository.findById(pantryItemId)
                .orElseThrow(() -> new IllegalArgumentException("Pantry item not found"));
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
