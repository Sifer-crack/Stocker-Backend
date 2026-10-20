package com.stocker.identity.infrastructure.messaging;

import com.stocker.identity.application.HouseholdEventService;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventConsumer {

	private final HouseholdEventService householdEventService;

	public KafkaEventConsumer(HouseholdEventService householdEventService) {
		this.householdEventService = householdEventService;
	}

	@KafkaListener(topics = "#{'${app.kafka.consumer.topics}'.split(',')}", groupId = "${spring.kafka.consumer.group-id}")
	public void onEvent(String payload) {
		// TODO: implement — extract topic/key from ConsumerRecord, delegate to application service.
	}

}
