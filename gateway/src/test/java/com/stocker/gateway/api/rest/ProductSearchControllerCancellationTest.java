package com.stocker.gateway.api.rest;

import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import com.stocker.pricing.api.grpc.v1.SearchProductsRequest;
import com.stocker.pricing.api.grpc.v1.SearchProductsResponse;
import com.stocker.pricing.api.grpc.v1.SelectProductRequest;
import com.stocker.pricing.api.grpc.v1.SelectProductResponse;
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

/** Dropping the HTTP request must cancel the gRPC call to pricing, for both product endpoints (see PricingControllerCancellationTest). */
class ProductSearchControllerCancellationTest {

	private final CountDownLatch started = new CountDownLatch(1);
	private final CountDownLatch serverSawCancel = new CountDownLatch(1);
	private volatile Throwable cancelCause;
	private Server server;
	private ManagedChannel channel;
	private ProductSearchController controller;

	@BeforeEach
	void startServer() throws Exception {
		server = ServerBuilder.forPort(0).addService(new BlockingUntilCancelled()).build().start();
		channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
		controller = new ProductSearchController(ProductSearchGrpc.newBlockingStub(channel));
	}

	@AfterEach
	void stopServer() throws Exception {
		channel.shutdownNow();
		server.shutdownNow();
		server.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void disposingProductSearchCancelsTheGrpcCall() throws Exception {
		assertCancelPropagates(controller.products("milk", "item-1", null, null).subscribe());
	}

	@Test
	void disposingSelectCancelsTheGrpcCall() throws Exception {
		assertCancelPropagates(controller.selectProduct("item-1",
				new ProductSearchController.SelectProductBody("anchor-blue-top-milk|2000ml")).subscribe());
	}

	private void assertCancelPropagates(Disposable subscription) throws Exception {
		assertThat(started.await(5, TimeUnit.SECONDS)).as("server call started").isTrue();
		subscription.dispose();
		assertThat(serverSawCancel.await(3, TimeUnit.SECONDS)).as("server saw the call cancelled promptly").isTrue();
		assertThat(cancelCause).as("cancelled by the client, not completed normally").isNotNull();
	}

	private final class BlockingUntilCancelled extends ProductSearchGrpc.ProductSearchImplBase {

		@Override
		public void searchProducts(SearchProductsRequest request, StreamObserver<SearchProductsResponse> out) {
			block();
		}

		@Override
		public void selectProduct(SelectProductRequest request, StreamObserver<SelectProductResponse> out) {
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
