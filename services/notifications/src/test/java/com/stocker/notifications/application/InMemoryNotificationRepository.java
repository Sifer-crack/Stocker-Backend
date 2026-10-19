package com.stocker.notifications.application;

import com.stocker.notifications.application.port.ExpirationNotificationRepository;
import com.stocker.notifications.domain.ExpirationNotification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Test fake for the repository port. */
public class InMemoryNotificationRepository implements ExpirationNotificationRepository {

    private final Map<UUID, ExpirationNotification> rows = new LinkedHashMap<>();

    @Override
    public ExpirationNotification save(ExpirationNotification notification) {
        rows.put(notification.getId(), notification);
        return notification;
    }

    @Override
    public boolean existsByPantryItemIdAndExpirationDate(UUID pantryItemId, LocalDate expirationDate) {
        return rows.values().stream().anyMatch(row -> row.getPantryItemId().equals(pantryItemId)
                && row.getExpirationDate().equals(expirationDate));
    }

    @Override
    public List<ExpirationNotification> findByUserIdNewestFirst(UUID userId) {
        List<ExpirationNotification> found = new ArrayList<>(rows.values().stream()
                .filter(row -> row.getUserId().equals(userId)).toList());
        found.sort(Comparator.comparing(ExpirationNotification::getCreatedAt).reversed());
        return found;
    }

    @Override
    public Optional<ExpirationNotification> findByIdAndUserId(UUID id, UUID userId) {
        return Optional.ofNullable(rows.get(id)).filter(row -> row.getUserId().equals(userId));
    }

    public int size() {
        return rows.size();
    }
}
