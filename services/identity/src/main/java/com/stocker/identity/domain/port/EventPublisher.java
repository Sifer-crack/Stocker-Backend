package com.stocker.identity.domain.port;

public interface EventPublisher {

	void publish(String topic, String key, String payload);

}
