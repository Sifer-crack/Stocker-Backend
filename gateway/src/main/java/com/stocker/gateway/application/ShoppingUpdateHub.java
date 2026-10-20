package com.stocker.gateway.application;

import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory fan-out from the internal "shopping item updated" callback to each user's open SSE
 * streams. Best effort by design: shopping has already persisted the state, so a user with no open
 * stream (or a slow one that gets skipped) simply reads it next time. State is per gateway
 * instance, so with several gateway replicas this needs a shared broker instead.
 */
@Component
public class ShoppingUpdateHub {

	/** What shopping posts: the owning user, how it was produced, and the item's full state. */
	public record ShoppingUpdate(String userId, String source, Map<String, Object> item) {
	}

	/** What a client receives. The owner id is routing information and is not sent to the browser. */
	public record Push(String source, Map<String, Object> item) {
	}

	private final Sinks.Many<ShoppingUpdate> sink = Sinks.many().multicast().directBestEffort();

	/** Emission must be serialized; callers are concurrent request threads. */
	public synchronized void publish(ShoppingUpdate update) {
		sink.tryEmitNext(update);
	}

	public Flux<Push> updatesFor(String userId) {
		return sink.asFlux()
				.filter(update -> userId.equals(update.userId()))
				.map(update -> new Push(update.source(), update.item()));
	}
}
