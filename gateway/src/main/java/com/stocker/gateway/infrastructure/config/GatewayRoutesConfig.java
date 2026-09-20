package com.stocker.gateway.infrastructure.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayRoutesConfig {

	// Routes here are for proxying to a service's own REST surface (none exist yet - every
	// service except pricing is still skeleton). A BFF-style endpoint that's implemented locally
	// and translates REST -> gRPC (e.g. api/rest/PricingController) does NOT need a route entry:
	// Spring Cloud Gateway's WebFlux dispatcher already routes to it as an ordinary controller.
	// HTTP routes live under spring.cloud.gateway.server.webflux.routes in
	// application.yml. Currently: /api/identity/** -> http://identity:8081
	// with StripPrefix=1 (/api/identity/register -> /identity/register). Claim forwarding to downstream services is handled
	// by JwtClaimForwardFilter; auth rules by SecurityConfig.

}
