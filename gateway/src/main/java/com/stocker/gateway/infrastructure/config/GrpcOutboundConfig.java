package com.stocker.gateway.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcOutboundConfig {

	// TODO: create proto-based blocking/reactive stubs once the services expose gRPC APIs.
	// Each service will expose a gRPC server; create one channel per service, e.g.:
	//
	//   @Bean
	//   IdentityGrpc.IdentityBlockingStub identityStub(GrpcChannelFactory channels) {
	//       return IdentityGrpc.newBlockingStub(channels.createChannel("identity"));
	//   }
	//
	// Channel targets live under spring.grpc.client.channels.<name>.address in application.yml.
	// Adding real stubs requires the spring-grpc protobuf build plugin + src/main/proto definitions.

	private final GrpcChannelFactory channelFactory;

	public GrpcOutboundConfig(GrpcChannelFactory channelFactory) {
		this.channelFactory = channelFactory;
	}

}
