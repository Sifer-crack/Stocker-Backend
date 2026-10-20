package com.stocker.gateway.infrastructure.config;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A hung pricing must not pin a boundedElastic thread forever: calls get a default deadline. */
class PricingDeadlineInterceptorTest {

	private final CountDownLatch release = new CountDownLatch(1);
	private Server server;
	private ManagedChannel channel;

	@BeforeEach
	void start() throws Exception {
		server = ServerBuilder.forPort(0).addService(new PriceRecordServiceGrpc.PriceRecordServiceImplBase() {
			@Override
			public void search(SearchRequest request, StreamObserver<SearchResponse> out) {
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				out.onNext(SearchResponse.getDefaultInstance());
				out.onCompleted();
			}
		}).build().start();
		channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
	}

	@AfterEach
	void stop() throws Exception {
		release.countDown();
		channel.shutdownNow();
		server.shutdownNow();
		server.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void aHungCallFailsWithDeadlineExceeded() {
		var stub = PriceRecordServiceGrpc.newBlockingStub(channel)
				.withInterceptors(new PricingDeadlineInterceptor(Duration.ofMillis(300)));
		assertThatThrownBy(() -> stub.search(SearchRequest.getDefaultInstance()))
				.isInstanceOfSatisfying(StatusRuntimeException.class,
						e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
	}

	@Test
	void aFastCallIsUnaffected() {
		release.countDown();
		var stub = PriceRecordServiceGrpc.newBlockingStub(channel)
				.withInterceptors(new PricingDeadlineInterceptor(Duration.ofSeconds(5)));
		assertThat(stub.search(SearchRequest.getDefaultInstance())).isNotNull();
	}

	@Test
	void anExplicitShorterDeadlineIsKept() {
		var stub = PriceRecordServiceGrpc.newBlockingStub(channel)
				.withInterceptors(new PricingDeadlineInterceptor(Duration.ofSeconds(30)))
				.withDeadlineAfter(200, TimeUnit.MILLISECONDS);
		long start = System.nanoTime();
		assertThatThrownBy(() -> stub.search(SearchRequest.getDefaultInstance()))
				.isInstanceOf(StatusRuntimeException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
	}
}
