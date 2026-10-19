package com.stocker.catalog.domain.port;

public interface EventPublisher {

	void publish(String topic, String key, String payload);

}
