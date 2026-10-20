package com.stocker.gateway.infrastructure.config;

import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** Stub for pricing's read-only store catalogue, on the same "pricing" channel as the other pricing calls. */
@Configuration
public class ChainCatalogOutboundConfig {

	@Bean
	public ChainCatalogGrpc.ChainCatalogBlockingStub chainCatalogBlockingStub(GrpcChannelFactory channels,
			@Value("${app.pricing.deadline:PT10S}") Duration deadline) {
		return ChainCatalogGrpc.newBlockingStub(channels.createChannel("pricing"))
				.withInterceptors(new PricingDeadlineInterceptor(deadline));
	}
}
