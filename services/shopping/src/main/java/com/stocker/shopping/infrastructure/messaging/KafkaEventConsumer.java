package com.stocker.shopping.infrastructure.messaging;

import com.stocker.shopping.application.ShoppingEventService;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventConsumer {

	private final ShoppingEventService shoppingEventService;

	public KafkaEventConsumer(ShoppingEventService shoppingEventService) {
		this.shoppingEventService = shoppingEventService;
	}

	@KafkaListener(topics = "#{'${app.kafka.consumer.topics}'.split(',')}", groupId = "${spring.kafka.consumer.group-id}")
	public void onEvent(String payload) {
		// TODO: implement — extract topic/key from ConsumerRecord, delegate to application service.
	}

}
