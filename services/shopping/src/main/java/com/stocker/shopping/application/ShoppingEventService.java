package com.stocker.shopping.application;

import com.stocker.shopping.domain.port.EventPublisher;

import org.springframework.stereotype.Service;

@Service
public class ShoppingEventService {

	private final EventPublisher eventPublisher;

	public ShoppingEventService(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	public void handleEvent(String topic, String key, String payload) {
		// TODO: implement domain logic for shopping list/item events.
	}

	public void publishEvent(String topic, String key, String payload) {
		eventPublisher.publish(topic, key, payload);
	}

}
