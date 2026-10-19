package com.stocker.catalog.infrastructure.messaging;

import com.stocker.catalog.application.CatalogEventService;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventConsumer {

	private final CatalogEventService catalogEventService;

	public KafkaEventConsumer(CatalogEventService catalogEventService) {
		this.catalogEventService = catalogEventService;
	}

	@KafkaListener(topics = "#{'${app.kafka.consumer.topics}'.split(',')}", groupId = "${spring.kafka.consumer.group-id}")
	public void onEvent(String payload) {
		// TODO: implement — extract topic/key from ConsumerRecord, delegate to application service.
	}

}
