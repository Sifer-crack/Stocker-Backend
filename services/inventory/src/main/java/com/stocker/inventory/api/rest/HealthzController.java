package com.stocker.analytics.api.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/healthz")
public class HealthzController {

	@GetMapping
	public Map<String, String> healthz() {
		return Map.of("service", "analytics", "status", "UP");
	}

}
