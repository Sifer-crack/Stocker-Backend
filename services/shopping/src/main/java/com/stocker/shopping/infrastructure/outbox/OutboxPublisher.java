package com.stocker.shopping.infrastructure.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {

	// TODO: poll the outbox table (see db/migration/V2__create_outbox.sql), publish
	// PENDING rows to app.kafka.producer.topics.shopping, then mark them published.
	// At-least-once delivery: only mark published after a successful Kafka send.
	// Handle duplicates via an idempotent key on the consumer side.

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
	public void publish() {
		// TODO: implement
	}

}
