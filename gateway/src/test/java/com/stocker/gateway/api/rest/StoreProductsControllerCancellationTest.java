package com.stocker.gateway.api.rest;

import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

/** Dropping the HTTP request must cancel the gRPC call to pricing for a favorite store's products too. */
class StoreProductsControllerCancellationTest {

	private final CountDownLatch started = new CountDownLatch(1);
	private final CountDownLatch serverSawCancel = new CountDownLatch(1);
	private volatile Throwable cancelCause;
	private Server server;
	private ManagedChannel channel;
	private StoreProductsController controller;

	@BeforeEach
	void startServer() throws Exception {
		server = ServerBuilder.forPort(0).addService(new BlockingUntilCancelled()).build().start();
		channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
		controller = new StoreProductsController(ChainCatalogGrpc.newBlockingStub(channel));
	}

	@AfterEach
	void stopServer() throws Exception {
		channel.shutdownNow();
		server.shutdownNow();
		server.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void disposingTheStoreProductsRequestCancelsTheGrpcCall() throws Exception {
		Disposable subscription = controller.products("paknsave", "milk", 0).subscribe();

		assertThat(started.await(5, TimeUnit.SECONDS)).as("server call started").isTrue();
		subscription.dispose();
		assertThat(serverSawCancel.await(3, TimeUnit.SECONDS)).as("server saw the call cancelled promptly").isTrue();
		assertThat(cancelCause).as("cancelled by the client, not completed normally").isNotNull();
	}

	private final class BlockingUntilCancelled extends ChainCatalogGrpc.ChainCatalogImplBase {

		@Override
		public void listProducts(ListChainProductsRequest request, StreamObserver<ListChainProductsResponse> out) {
			Context.current().addListener(ctx -> {
				cancelCause = ctx.cancellationCause();
				serverSawCancel.countDown();
			}, Runnable::run);
			started.countDown();
			try {
				serverSawCancel.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
