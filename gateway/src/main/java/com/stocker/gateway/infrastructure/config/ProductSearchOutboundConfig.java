package com.stocker.gateway.infrastructure.config;

import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** Stub for pricing's merged-product search (same "pricing" channel as the price-record stub). */
@Configuration
public class ProductSearchOutboundConfig {

	@Bean
	public ProductSearchGrpc.ProductSearchBlockingStub productSearchBlockingStub(GrpcChannelFactory channels) {
		return ProductSearchGrpc.newBlockingStub(channels.createChannel("pricing"));
	}
}
