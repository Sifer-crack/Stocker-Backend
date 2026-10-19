package com.stocker.shopping.domain.port;

public interface EventPublisher {

	void publish(String topic, String key, String payload);

}
