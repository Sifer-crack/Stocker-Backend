package com.stocker.shopping.infrastructure.config;

import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

	// Channel target lives under spring.grpc.client.channels.pricing (STOCKER_GRPC_PRICING_ADDRESS).
	@Bean
	public PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceRecordServiceBlockingStub(
			GrpcChannelFactory channels) {
		return PriceRecordServiceGrpc.newBlockingStub(channels.createChannel("pricing"));
	}
}
