package com.stocker.inventory.infrastructure.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CatalogClient {
    private final RestClient restClient;

    // use the catalog api
    public CatalogClient() {
        this.restClient = RestClient.builder().baseUrl("http://localhost:8082").build();
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
