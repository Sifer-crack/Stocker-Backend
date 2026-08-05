package com.stocker.catalog.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaProducerConfig {

	// TODO: wire KafkaTemplate<String, String> and expose produce methods.
	// Producer conventions:
	// - acks=all (configured in application.yml)
	// - key = householdId or itemId (see infra/kafka/topics.yml)
	// - consumers use at-least-once delivery, so producers must tolerate retries.

}
