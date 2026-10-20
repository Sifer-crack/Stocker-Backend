package com.stocker.shopping.infrastructure.notify;

import com.stocker.shopping.application.model.ItemUpdate;
import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.UpdateSource;
import com.stocker.shopping.application.port.ComparisonNotifier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pushes an item's full, current state to the gateway's internal endpoint, which relays it to the
 * user's open frontend stream. Runs on its own executor and swallows every failure: the state is
 * already committed, so a missed push only means the client sees it on its next read.
 */
@Component
public class GatewayComparisonNotifier implements ComparisonNotifier {

	static final String PATH = "/internal/shopping/updates";
	static final String TOKEN_HEADER = "X-Internal-Token";

	private static final Logger log = LoggerFactory.getLogger(GatewayComparisonNotifier.class);

	private final RestClient gateway;
	private final Executor executor;
	private final String internalToken;

	public GatewayComparisonNotifier(@Qualifier("gatewayRestClient") RestClient gateway,
			@Qualifier("notificationExecutor") Executor executor,
			@Value("${app.gateway.internal-token}") String internalToken) {
		this.gateway = gateway;
		this.executor = executor;
		this.internalToken = internalToken;
	}

	@Override
	public void itemUpdated(String userId, UpdateSource source, ItemView item) {
		ItemUpdate update = new ItemUpdate(userId, source.wire(), item);
		try {
			executor.execute(() -> push(update));
		} catch (RejectedExecutionException e) {
			log.warn("Notification queue full; dropping push for itemId={}", item.id());
		}
	}

	private void push(ItemUpdate update) {
		try {
			gateway.post()
					.uri(PATH)
					.header(TOKEN_HEADER, internalToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(update)
					.retrieve()
					.toBodilessEntity();
		} catch (RuntimeException e) {
			log.warn("Gateway push failed for itemId={} ({}); client will see it on next read",
					update.item().id(), e.getMessage());
		}
	}
}
