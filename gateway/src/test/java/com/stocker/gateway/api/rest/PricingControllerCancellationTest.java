package com.stocker.gateway.api.rest;

import com.stocker.pricing.api.grpc.v1.CompareShoppingListRequest;
import com.stocker.pricing.api.grpc.v1.CompareShoppingListResponse;
import com.stocker.pricing.api.grpc.v1.MatchItemRequest;
import com.stocker.pricing.api.grpc.v1.MatchItemResponse;
import com.stocker.pricing.api.grpc.v1.PriceRecordServiceGrpc;
import com.stocker.pricing.api.grpc.v1.SearchRequest;
import com.stocker.pricing.api.grpc.v1.SearchResponse;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A client that drops the HTTP request disposes the reactive subscription; that must reach the pricing server as
 * a cancelled gRPC call (its Context is cancelled with a cause), for every pricing endpoint. Runs a real gRPC
 * server on loopback whose handlers block until their call is cancelled.
 */
class PricingControllerCancellationTest {

	private final CountDownLatch started = new CountDownLatch(1);
	private final CountDownLatch serverSawCancel = new CountDownLatch(1);
	private volatile Throwable cancelCause;
	private Server server;
	private ManagedChannel channel;
	private PricingController controller;

	@BeforeEach
	void startServer() throws Exception {
		server = ServerBuilder.forPort(0).addService(new BlockingUntilCancelled()).build().start();
		channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
		controller = new PricingController(PriceRecordServiceGrpc.newBlockingStub(channel));
	}

	@AfterEach
	void stopServer() throws Exception {
		channel.shutdownNow();
		server.shutdownNow();
		server.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void disposingSearchCancelsTheGrpcCall() throws Exception {
		assertCancelPropagates(controller.search("milk", "item-1", null, null).subscribe());
	}

	@Test
	void disposingCompareCancelsTheGrpcCall() throws Exception {
		assertCancelPropagates(controller.compare(new PricingController.CompareShoppingListRequestBody(
				List.of(new PricingController.ShoppingListItemBody("item-1", 1)), "auckland", null)).subscribe());
	}

	@Test
	void disposingMatchCancelsTheGrpcCall() throws Exception {
		assertCancelPropagates(controller.match("milk", "item-1", null, null, null).subscribe());
	}

	private void assertCancelPropagates(Disposable subscription) throws Exception {
		assertThat(started.await(5, TimeUnit.SECONDS)).as("server call started").isTrue();
		subscription.dispose();
		assertThat(serverSawCancel.await(3, TimeUnit.SECONDS)).as("server saw the call cancelled promptly").isTrue();
		assertThat(cancelCause).as("cancelled by the client, not completed normally").isNotNull();
	}

	private final class BlockingUntilCancelled extends PriceRecordServiceGrpc.PriceRecordServiceImplBase {

		@Override
		public void search(SearchRequest request, StreamObserver<SearchResponse> out) {
			block();
		}

		@Override
		public void compareShoppingList(CompareShoppingListRequest request,
				StreamObserver<CompareShoppingListResponse> out) {
			block();
		}

		@Override
		public void matchItem(MatchItemRequest request, StreamObserver<MatchItemResponse> out) {
			block();
		}

		private void block() {
			Context context = Context.current();
			context.addListener(ctx -> {
				cancelCause = ctx.cancellationCause();
				serverSawCancel.countDown();
			}, Runnable::run);
			started.countDown();
			try {
				serverSawCancel.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
