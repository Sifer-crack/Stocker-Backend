package com.stocker.pricing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocker.pricing.cancel.CancellationToken;
import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.cache.PriceCache;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import io.grpc.Context;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The legacy third-party-provider search path stops with its request and never queues refresh work for a gone caller. */
class PriceSearchServiceCancellationTest {

	private WebFetcher webFetcher;
	private PriceRecordRepository repository;
	private PriceCache priceCache;
	private KafkaTemplate<String, String> kafkaTemplate;
	private ExecutorService executor;
	private PriceSearchService service;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		webFetcher = mock(WebFetcher.class);
		repository = mock(PriceRecordRepository.class);
		priceCache = mock(PriceCache.class);
		kafkaTemplate = mock(KafkaTemplate.class);
		executor = Executors.newCachedThreadPool();
		when(priceCache.getFresh(any())).thenReturn(List.of());
		when(priceCache.getStale(any())).thenReturn(List.of());
		PricingCacheProperties properties = new PricingCacheProperties();
		properties.getFallback().setTimeout(java.time.Duration.ofSeconds(30));
		service = new PriceSearchService(webFetcher, repository, priceCache, properties, mock(EventPublisher.class),
				executor, kafkaTemplate, new ObjectMapper(), "topic", mock(PriceStatsService.class));
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	@Test
	void aTokenCancelledBeforeTheCallNeverReachesTheProvider() {
		CancellationToken token = CancellationToken.create();
		token.cancel();

		List<PriceRecord> result = service.search("rice", "item-1", List.of(), null, token);

		assertThat(result).isEmpty();
		verifyNoInteractions(webFetcher);
		verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(String.class));
	}

	@Test
	void cancellingWhileTheProviderCallIsInFlightCancelsItAndQueuesNoRefresh() throws Exception {
		CountDownLatch inFlight = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		CountDownLatch ended = new CountDownLatch(1);
		CancellationToken token = CancellationToken.create();
		when(webFetcher.fetch(any(WebFetchRequest.class))).thenAnswer(invocation -> {
			WebFetchRequest request = invocation.getArgument(0);
			assertThat(request.getCancellation()).isSameAs(token);       // the provider is handed the token
			inFlight.countDown();
			try {
				Thread.sleep(20_000);
			} catch (InterruptedException e) {
				interrupted.set(true);
			}
			ended.countDown();
			return List.of();
		});
		var caller = executor.submit(() -> service.search("rice", "item-1", List.of(), null, token));
		assertThat(inFlight.await(5, TimeUnit.SECONDS)).isTrue();

		token.cancel();

		assertThat(caller.get(3, TimeUnit.SECONDS)).isEmpty();
		assertThat(ended.await(3, TimeUnit.SECONDS)).isTrue();
		assertThat(interrupted).isTrue();
		verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(String.class));
		verify(repository, never()).save(any());
	}

	@Test
	void theFourArgumentSearchFollowsTheCurrentGrpcContext() throws Exception {
		Context.CancellableContext context = Context.current().withCancellation();
		context.cancel(new RuntimeException("client went away"));

		List<PriceRecord> result = context.call(() -> service.search("rice", "item-1", List.of(), null));

		assertThat(result).isEmpty();
		verifyNoInteractions(webFetcher);
	}

	@Test
	void refreshFromProviderRefusesToStartWhenCancelled() {
		CancellationToken token = CancellationToken.create();
		token.cancel();

		assertThatThrownBy(() -> service.refreshFromProvider("rice", "item-1", List.of(), null, token))
				.isInstanceOf(CancellationException.class);
		verifyNoInteractions(webFetcher);
	}
}
