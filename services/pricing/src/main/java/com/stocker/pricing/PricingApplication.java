package com.stocker.pricing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*
 * TODO(price-engine): outstanding module-level work.
 *
 * 1. Structure: this module still uses the legacy model/ + repository/ + service/ layout.
 *    The rest of the repo uses api/rest, application, domain, infrastructure. No service may
 *    diverge structurally (root AGENT.md), so migrate the classes accordingly.
 *
 * 2. Missing files that must be created (they do not exist yet; nothing here references them):
 *      domain/port/EventPublisher.java
 *      infrastructure/messaging/KafkaEventPublisher.java
 *      infrastructure/messaging/KafkaEventConsumer.java   (consumes stocker.catalog.events.v1)
 *      application/PricingEventService.java
 *    Then wire the app.kafka.* topics against infra/kafka/topics.yml.
 *
 * 3. gRPC: the server is not started (no Spring gRPC server starter / spring.grpc.server config),
 *    so api/grpc/PriceRecordGrpcController is never bound. See build.gradle and application.yml.
 *
 * 4. Tests: only GET /healthz is covered. Add gRPC and PriceFetcherService tests.
 *
 * 5. Docs: README.md and AGENT.md still claim a live gRPC server and a retained EventPublisher.
 */
@SpringBootApplication
public class PricingApplication {

	public static void main(String[] args) {
		SpringApplication.run(PricingApplication.class, args);
	}

}
