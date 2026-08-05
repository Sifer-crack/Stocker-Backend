package com.stocker.pricing.infrastructure.publisher;

public interface EventPublisher {

	// TODO: define a stable contract for publishing domain events.
	// Producers follow at-least-once delivery; consumers must be idempotent.

	void publish(String topic, String key, String payload);

}
