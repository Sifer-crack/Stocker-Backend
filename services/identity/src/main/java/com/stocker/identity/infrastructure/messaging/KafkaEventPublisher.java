package com.stocker.identity.infrastructure.messaging;

import com.stocker.identity.domain.port.EventPublisher;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher implements EventPublisher {

	private final KafkaTemplate<String, String> kafkaTemplate;

	public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@Override
	public void publish(String topic, String key, String payload) {
		// TODO: implement via KafkaTemplate. acks=all configured in application.yml.
	}

}
