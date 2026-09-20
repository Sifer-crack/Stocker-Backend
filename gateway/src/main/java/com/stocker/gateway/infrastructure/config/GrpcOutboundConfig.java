package com.stocker.gateway.infrastructure.config;

import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcOutboundConfig {

	// TODO: add IdentityGrpc.IdentityBlockingStub once identity exposes a real gRPC API
	// (channel target already configured under spring.grpc.client.channels.identity).

	@Bean
	public PriceRecordServiceGrpc.PriceRecordServiceBlockingStub priceRecordServiceBlockingStub(
			GrpcChannelFactory channels) {
		return PriceRecordServiceGrpc.newBlockingStub(channels.createChannel("pricing"));
	}
}
