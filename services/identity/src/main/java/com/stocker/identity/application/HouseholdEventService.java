package com.stocker.identity.application;

import com.stocker.identity.domain.port.EventPublisher;

import org.springframework.stereotype.Service;

@Service
public class HouseholdEventService {

	private final EventPublisher eventPublisher;

	public HouseholdEventService(EventPublisher eventPublisher) {
		this.eventPublisher = eventPublisher;
	}

	public void handleEvent(String topic, String key, String payload) {
		// TODO: implement domain logic for household events.
		// Delegate to domain entities/aggregates here.
	}

	public void publishEvent(String topic, String key, String payload) {
		eventPublisher.publish(topic, key, payload);
	}

}
