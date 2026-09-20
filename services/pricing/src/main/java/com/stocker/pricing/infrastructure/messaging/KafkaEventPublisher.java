package com.stocker.pricing.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.model.PriceRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes to {@code stocker.pricing.events.v1} — the public, cross-service domain-event stream. */
@Component
public class KafkaEventPublisher implements EventPublisher {

	private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
	private static final String EVENT_TYPE = "PriceRecordCaptured";

	private final KafkaTemplate<String, String> kafkaTemplate;
	private final ObjectMapper objectMapper;
	private final String pricingEventsTopic;

	public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
			@Value("${app.kafka.producer.topics.pricing}") String pricingEventsTopic) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
		this.pricingEventsTopic = pricingEventsTopic;
	}

	@Override
	public void publishPriceRecordCaptured(PriceRecord record) {
		try {
			String payload = objectMapper.writeValueAsString(new PriceRecordCapturedEvent(
					EVENT_TYPE,
					record.getId() == null ? null : record.getId().toString(),
					record.getItemId(),
					record.getStoreId(),
					record.getChainId(),
					record.getChannel(),
					record.getPriceAmount(),
					record.getCurrency(),
					record.isPromoFlag(),
					record.getCapturedAt()));
			kafkaTemplate.send(pricingEventsTopic, record.getItemId(), payload);
		} catch (Exception e) {
			log.error("Failed to publish PriceRecordCaptured for itemId={}", record.getItemId(), e);
		}
	}

	private record PriceRecordCapturedEvent(
			String eventType,
			String id,
			String itemId,
			String storeId,
			String chainId,
			String channel,
			BigDecimal priceAmount,
			String currency,
			boolean promoFlag,
			OffsetDateTime capturedAt) {
	}
}
