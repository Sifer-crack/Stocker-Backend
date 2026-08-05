package com.stocker.pricing.infrastructure.publisher;

import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher implements EventPublisher {

	// TODO: implement via KafkaTemplate<String, String>.
	// Producer conventions:
	// - acks=all (configured in application.yml)
	// - key = householdId (see infra/kafka/topics.yml)
	// - at-least-once delivery; consumers must be idempotent.

	@Override
	public void publish(String topic, String key, String payload) {
		// TODO: implement
	}

}
