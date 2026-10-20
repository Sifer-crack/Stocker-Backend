package com.stocker.shopping.infrastructure.config;

import com.stocker.shopping.infrastructure.messaging.PriceEventHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;

@Configuration
public class KafkaConsumerConfig {

	// Topic names come from app.kafka.consumer.topics in application.yml (stocker.pricing.events.v1).
	// At-least-once: the container commits offsets only after this returns without throwing.
	// Idempotency lives in PriceEventHandler -> ComparisonUpdateService (newer-observation-only merge),
	// so redelivery is harmless. This must stay the ONLY listener in this consumer group.
	private final PriceEventHandler handler;

	public KafkaConsumerConfig(PriceEventHandler handler) {
		this.handler = handler;
	}

	@KafkaListener(topics = "#{'${app.kafka.consumer.topics}'.split(',')}", groupId = "${spring.kafka.consumer.group-id}")
	public void onEvent(String payload) {
		handler.handle(payload);
	}
}
