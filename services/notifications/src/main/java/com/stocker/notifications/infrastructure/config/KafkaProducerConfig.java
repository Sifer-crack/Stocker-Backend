package com.stocker.notifications.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaProducerConfig {

	// TODO: wire KafkaTemplate<String, String> and expose produce methods IF this
	// service ever publishes to Kafka. Today notifications is consume-only
	// (stateless outbound fan-out), so this config is intentionally empty.

}
