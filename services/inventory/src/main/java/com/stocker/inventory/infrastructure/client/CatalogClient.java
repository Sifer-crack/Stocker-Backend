package com.stocker.inventory.infrastructure.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CatalogClient {
    private final RestClient restClient;

    // use the catalog api
    // base URL: STOCKER_CATALOG_URL (docker compose passes http://catalog:8082); default is the local-run value
    public CatalogClient(@Value("${app.catalog.base-url:http://localhost:8082}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public ProductResponse findByNameAndUnit(String name, String unit) {
        try {
            return restClient.get().uri(uriBuilder -> uriBuilder
                    .path("/products/search")
                    .queryParam("name", name)
                    .queryParam("unit", unit)
                    .build())
                    .retrieve()
                    .body(ProductResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    /** Returns null on 404; other failures propagate (callers treat the catalog as optional). */
    public ProductResponse findById(java.util.UUID productId) {
        try {
            return restClient.get().uri("/products/{id}", productId).retrieve().body(ProductResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public ProductResponse createProduct(String name, String unit) {
        return restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/products")
                        .queryParam("name", name)
                        .queryParam("unit", unit)
                        .build())
                .retrieve()
                .body(ProductResponse.class);
    }

}
