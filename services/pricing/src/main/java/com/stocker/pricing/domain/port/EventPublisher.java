package com.stocker.pricing.domain.port;

import com.stocker.pricing.model.PriceRecord;

public interface EventPublisher {

	/** Publishes a PriceRecordCaptured event for downstream consumers (shopping, analytics, notifications). */
	void publishPriceRecordCaptured(PriceRecord record);
}
