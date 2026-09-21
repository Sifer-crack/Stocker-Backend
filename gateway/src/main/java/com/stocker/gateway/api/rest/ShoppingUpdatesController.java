package com.stocker.gateway.api.rest;

import com.stocker.gateway.application.ShoppingUpdateHub;
import com.stocker.gateway.application.ShoppingUpdateHub.Push;
import com.stocker.gateway.application.ShoppingUpdateHub.ShoppingUpdate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Gateway -> frontend push for shopping-list comparison updates.
 *
 * <p>Shopping posts an item's full state to {@code /internal/shopping/updates} (shared-secret
 * guarded, never JWT) whenever a comparison is stored, whether from the immediate gRPC result or a
 * later Kafka backfill. This controller relays it, unchanged, to that user's
 * {@code GET /api/updates/shopping} server-sent-event stream. The stream path is deliberately
 * outside {@code /api/shopping/**}, which is proxied to the shopping service.
 */
@RestController
public class ShoppingUpdatesController {

	static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
	static final String EVENT_NAME = "shopping-item-updated";
	private static final Duration HEARTBEAT = Duration.ofSeconds(25);

	private final ShoppingUpdateHub hub;
	private final byte[] internalToken;

	public ShoppingUpdatesController(ShoppingUpdateHub hub, @Value("${stocker.internal.token:}") String internalToken) {
		this.hub = hub;
		this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The heartbeat is an SSE comment: it keeps idle connections from being closed by proxies and
	 * lets the client notice a dead connection, without being delivered as an application event.
	 * The first one fires immediately so response headers are sent as soon as the stream opens
	 * (a browser's fetch() would otherwise wait a full interval to learn the connection is live).
	 */
	@GetMapping(value = "/api/updates/shopping", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Push>> stream(@AuthenticationPrincipal Jwt jwt) {
		Flux<ServerSentEvent<Push>> updates = hub.updatesFor(jwt.getSubject())
				.map(push -> ServerSentEvent.<Push>builder().event(EVENT_NAME).data(push).build());
		Flux<ServerSentEvent<Push>> heartbeat = Flux.interval(Duration.ZERO, HEARTBEAT)
				.map(tick -> ServerSentEvent.<Push>builder().comment("keep-alive").build());
		return Flux.merge(updates, heartbeat);
	}

	@PostMapping("/internal/shopping/updates")
	public Mono<ResponseEntity<Map<String, String>>> receive(
			@RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
			@RequestBody ShoppingUpdate update) {
		if (!validToken(token)) {
			return Mono.just(error(HttpStatus.UNAUTHORIZED, "unauthorized"));
		}
		if (update.userId() == null || update.userId().isBlank() || update.item() == null) {
			return Mono.just(error(HttpStatus.BAD_REQUEST, "userId and item are required"));
		}
		hub.publish(update);
		return Mono.just(ResponseEntity.accepted().build());
	}

	/** Constant-time, and closed when no token is configured. */
	private boolean validToken(String provided) {
		return internalToken.length > 0
				&& provided != null
				&& MessageDigest.isEqual(internalToken, provided.getBytes(StandardCharsets.UTF_8));
	}

	private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Map.of("error", message));
	}
}
