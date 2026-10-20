package com.stocker.notifications.infrastructure.client;

import com.stocker.notifications.application.model.ExpiringItem;
import com.stocker.notifications.application.port.PantryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** Reads the expiring-items list from the inventory service (service-to-service, not via the gateway). */
@Component
public class InventoryPantryClient implements PantryClient {

	private final RestClient restClient;

	public InventoryPantryClient(@Value("${app.inventory.base-url}") String baseUrl) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(10));
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	@Override
	public List<ExpiringItem> findDueItems(LocalDate asOf) {
		List<ExpiringItem> items = restClient.get()
				.uri(uri -> uri.path("/internal/pantry-items/expiring").queryParam("asOf", asOf).build())
				.retrieve()
				.body(new ParameterizedTypeReference<List<ExpiringItem>>() {
				});
		return items == null ? List.of() : items;
	}
}
