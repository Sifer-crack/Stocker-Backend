package com.stocker.shopping.application;

import java.util.UUID;

/** Published inside the add-item transaction; acted on only after it commits. */
public record ItemAddedEvent(UUID itemId) {
}
