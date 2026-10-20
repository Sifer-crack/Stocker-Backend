package com.stocker.catalog.application;

import com.stocker.catalog.domain.port.EventPublisher;

import org.springframework.stereotype.Service;

@Service
public class CatalogEventService {

	private final EventPublisher eventPublisher;

	public CatalogEventService(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	public void handleEvent(String topic, String key, String payload) {
		// TODO: implement domain logic for catalog item events.
	}

	public void publishEvent(String topic, String key, String payload) {
		eventPublisher.publish(topic, key, payload);
	}

}
