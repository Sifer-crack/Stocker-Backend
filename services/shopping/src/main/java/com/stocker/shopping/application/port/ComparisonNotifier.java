package com.stocker.shopping.application.port;

import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.UpdateSource;

/**
 * Tells the gateway that an item's comparison changed so it can relay it to the frontend. The one
 * notification path for both the synchronous gRPC result and the Kafka backfill. Best effort: the
 * state is already persisted, so implementations must never throw.
 */
public interface ComparisonNotifier {

	void itemUpdated(String userId, UpdateSource source, ItemView item);
}
