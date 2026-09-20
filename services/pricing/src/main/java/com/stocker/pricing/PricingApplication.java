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
 * 2. Event pipeline: domain/port/EventPublisher + infrastructure/messaging/KafkaEventPublisher
 *    now exist (publish PriceRecordCaptured to stocker.pricing.events.v1). Still missing:
 *    infrastructure/messaging/KafkaEventConsumer + application/PricingEventService for actually
 *    consuming stocker.catalog.events.v1 (infrastructure/config/KafkaConsumerConfig is a stub only).
 *
 * 3. gRPC: server now starts (Spring gRPC server starter, spring.grpc.server.port), and
 *    api/grpc/PriceRecordGrpcController is a @GrpcService. See CACHE_MODULE.md for the on-demand
 *    cache-aside + async refresh built on top of Search.
 *
 * 4. Tests: gRPC Search/save-path, PriceSearchService cache-aside, and PriceCache are covered.
 *    PriceFetcherService (Fetch/Save entry points) still has no dedicated unit test.
 */
@SpringBootApplication
public class PricingApplication {

	public static void main(String[] args) {
		SpringApplication.run(PricingApplication.class, args);
	}

}
