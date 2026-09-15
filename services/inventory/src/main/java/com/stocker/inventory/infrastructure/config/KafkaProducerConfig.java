package com.stocker.analytics.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaProducerConfig {

	// TODO: wire KafkaTemplate<String, String> and expose produce methods IF this
	// service ever publishes to Kafka. Today analytics is consume-only (event sink),
	// so this config is intentionally empty.

}
