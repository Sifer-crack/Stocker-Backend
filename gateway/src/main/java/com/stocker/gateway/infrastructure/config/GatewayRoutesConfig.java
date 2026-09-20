package com.stocker.gateway.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

	// HTTP routes live under spring.cloud.gateway.server.webflux.routes in
	// application.yml. Currently: /api/identity/** -> http://identity:8081
	// with StripPrefix=2. Claim forwarding to downstream services is handled
	// by JwtClaimForwardFilter; auth rules by SecurityConfig.

}
