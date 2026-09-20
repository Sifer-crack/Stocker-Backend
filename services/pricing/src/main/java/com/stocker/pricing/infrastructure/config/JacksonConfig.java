package com.stocker.pricing.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4's spring-boot-jackson module autoconfigures Jackson 3
 * (tools.jackson.databind.ObjectMapper) as the primary ObjectMapper bean, not the classic
 * com.fasterxml.jackson.databind.ObjectMapper this codebase's Kafka payload (de)serialization
 * (KafkaEventPublisher, PriceSearchService, PriceRefreshRequestConsumer) already uses and is
 * tested against. Rather than migrate to the very new Jackson 3 API, this explicitly provides
 * the classic Jackson 2 ObjectMapper bean these classes ask for - JavaTimeModule is required
 * since KafkaEventPublisher's event payload includes a raw OffsetDateTime field.
 */
@Configuration
public class JacksonConfig {

	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper().registerModule(new JavaTimeModule());
	}
}
