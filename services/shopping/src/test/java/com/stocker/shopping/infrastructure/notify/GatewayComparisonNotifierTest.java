package com.stocker.shopping.infrastructure.notify;

import com.stocker.shopping.application.model.ItemView;
import com.stocker.shopping.application.model.PriceView;
import com.stocker.shopping.application.model.UpdateSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GatewayComparisonNotifierTest {

	private MockRestServiceServer server;
	private GatewayComparisonNotifier notifier;
	private ItemView item;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://gateway");
		server = MockRestServiceServer.bindTo(builder).build();
		notifier = new GatewayComparisonNotifier(builder.build(), Runnable::run, "secret");
		OffsetDateTime now = OffsetDateTime.of(2026, 9, 21, 12, 0, 0, 0, ZoneOffset.UTC);
		PriceView cheapest = new PriceView("paknsave", "ps:1", new BigDecimal("4.20"), "NZD", false, now);
		item = new ItemView(UUID.randomUUID(), "milk", null, "milk", null, 1, "available", cheapest,
				List.of(cheapest), now, now);
	}

	@Test
	void postsTheFullItemStateWithTheInternalTokenToTheGateway() {
		server.expect(requestTo("http://gateway/internal/shopping/updates"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Internal-Token", "secret"))
				.andExpect(jsonPath("$.userId").value("user-1"))
				.andExpect(jsonPath("$.source").value("backfill"))
				.andExpect(jsonPath("$.item.comparisonStatus").value("available"))
				.andExpect(jsonPath("$.item.cheapest.chainId").value("paknsave"))
				.andExpect(jsonPath("$.item.cheapest.priceAmount").value(4.20))
				.andExpect(jsonPath("$.item.prices[0].storeId").value("ps:1"))
				.andRespond(withSuccess());

		notifier.itemUpdated("user-1", UpdateSource.BACKFILL, item);

		server.verify();
	}

	@Test
	void aFailingGatewayNeverThrowsBecauseTheStateIsAlreadyPersisted() {
		server.expect(requestTo("http://gateway/internal/shopping/updates")).andRespond(withServerError());

		assertDoesNotThrow(() -> notifier.itemUpdated("user-1", UpdateSource.SYNC, item));
		server.verify();
	}

	@Test
	void aFullNotificationQueueIsSwallowed() {
		GatewayComparisonNotifier saturated = new GatewayComparisonNotifier(RestClient.create("http://gateway"), task -> {
			throw new RejectedExecutionException("full");
		}, "secret");

		assertDoesNotThrow(() -> saturated.itemUpdated("user-1", UpdateSource.SYNC, item));
	}
}
