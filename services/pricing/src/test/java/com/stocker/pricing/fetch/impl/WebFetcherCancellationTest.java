package com.stocker.pricing.fetch.impl;

import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.fetch.config.WebFetcherProperties;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline: the "supermarket / provider" is a WebClient exchange function that never answers. */
class WebFetcherCancellationTest {

	private final AtomicInteger exchanges = new AtomicInteger();
	private final CountDownLatch exchangeCancelled = new CountDownLatch(1);
	private final CountDownLatch exchangeStarted = new CountDownLatch(1);

	private WebClient neverAnsweringClient() {
		return WebClient.builder().exchangeFunction(request -> Mono.<ClientResponse>never()
				.doOnSubscribe(s -> {
					exchanges.incrementAndGet();
					exchangeStarted.countDown();
				})
				.doOnCancel(exchangeCancelled::countDown)).build();
	}

	private SpreadWebFetcher spread() {
		WebFetcherProperties properties = new WebFetcherProperties();
		properties.getSpread().setApiKey("test-key");
		return new SpreadWebFetcher(neverAnsweringClient(), properties);
	}

	@Test
	void cancellingTheTokenCancelsTheInFlightHttpCall() throws Exception {
		CancellationToken token = CancellationToken.create();
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> call = pool.submit(() -> spread().fetch(WebFetchRequest.builder().searchTerm("milk").cancellation(token).build()));
			assertThat(exchangeStarted.await(5, TimeUnit.SECONDS)).isTrue();

			token.cancel();

			assertThat(exchangeCancelled.await(3, TimeUnit.SECONDS)).as("outbound subscription disposed").isTrue();
			assertThatThrownBy(() -> call.get(3, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(CancellationException.class);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aTokenCancelledBeforeTheCallSendsNothing() {
		CancellationToken token = CancellationToken.create();
		token.cancel();

		assertThatThrownBy(() -> spread().fetch(WebFetchRequest.builder().searchTerm("milk").cancellation(token).build()))
				.isInstanceOf(CancellationException.class);

		assertThat(exchanges).hasValue(0);
	}

	@Test
	void aRequestWithoutATokenIsNeverCancellable() {
		assertThat(WebFetchRequest.builder().build().getCancellation()).isSameAs(CancellationToken.NONE);
	}
}
