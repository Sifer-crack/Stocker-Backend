package com.stocker.notifications.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;

@Configuration
public class KafkaConsumerConfig {

	// TODO: implement idempotent, at-least-once handling.
	// Idempotency key: (topic, partition, offset). Dedupe store to be added later.
	// Topic names come from app.kafka.consumer.topics in application.yml.
	// This service consumes every Stocker event topic and fans out outbound notifications.

	@KafkaListener(topics = "#{'${app.kafka.consumer.topics}'.split(',')}", groupId = "${spring.kafka.consumer.group-id}")
	public void onEvent(String payload) {
		// TODO: implement
	}

}
