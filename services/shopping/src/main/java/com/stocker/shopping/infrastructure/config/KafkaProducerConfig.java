package com.stocker.shopping.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaProducerConfig {

	// TODO: wire KafkaTemplate<String, String> and expose produce methods.
	// Producer conventions:
	// - acks=all (configured in application.yml)
	// - key = householdId (see infra/kafka/topics.yml)
	// - shopping publishes via the OUTBOX: write to the outbox table in the domain
	//   transaction, then OutboxPublisher forwards to Kafka (at-least-once).
	// Prefer a KafkaTemplate backed by a Transactional outbox send.

}
