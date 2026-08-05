package com.stocker.gateway.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

	// TODO: define real HTTP routes under spring.cloud.gateway.server.webflux.routes in
	// application.yml once the services expose REST surfaces (currently only /healthz).
	// REST comes in here; gRPC fan-out to services is handled via GrpcOutboundConfig.

}
