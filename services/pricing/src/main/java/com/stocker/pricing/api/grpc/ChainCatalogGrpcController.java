package com.stocker.pricing.api.grpc;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import com.stocker.pricing.api.grpc.v1.ChainProduct;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import com.stocker.pricing.service.ChainProductService;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService
public class ChainCatalogGrpcController extends ChainCatalogGrpc.ChainCatalogImplBase {

	private static final Logger log = LoggerFactory.getLogger(ChainCatalogGrpcController.class);

	private final ChainProductService chainProductService;

	public ChainCatalogGrpcController(ChainProductService chainProductService) {
		this.chainProductService = chainProductService;
	}

	@Override
	public void listProducts(ListChainProductsRequest request, StreamObserver<ListChainProductsResponse> responseObserver) {
		if (isCancelled(responseObserver)) {
			return;   // the client is gone: do no work and write nothing
		}
		log.info("gRPC listProducts: chain={}, query='{}', limit={}", request.getChainId(), request.getQuery(),
				request.getLimit());
		java.util.List<ChainProductService.ChainProduct> products;
		try {
			products = chainProductService.list(request.getChainId(), request.getQuery(), request.getLimit());
		}
		catch (IllegalArgumentException ex) {
			if (!isCancelled(responseObserver)) {
				responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
			}
			return;
		}
		ListChainProductsResponse.Builder response = ListChainProductsResponse.newBuilder();
		products.forEach(product -> response.addProducts(toProto(product)));
		reply(responseObserver, response.build());
	}

	/** True when the caller has gone away (gRPC Context cancelled or the call itself cancelled). */
	private static boolean isCancelled(StreamObserver<?> responseObserver) {
		boolean cancelled = Context.current().isCancelled()
				|| (responseObserver instanceof ServerCallStreamObserver<?> call && call.isCancelled());
		if (cancelled) {
			log.info("gRPC listProducts cancelled by the client; stopping");
		}
		return cancelled;
	}

	/** Answers unless the call was cancelled meanwhile; a late CANCELLED status is swallowed. */
	private static <T> void reply(StreamObserver<T> responseObserver, T value) {
		if (isCancelled(responseObserver)) {
			return;
		}
		try {
			responseObserver.onNext(value);
			responseObserver.onCompleted();
		}
		catch (StatusRuntimeException e) {
			if (e.getStatus().getCode() != Status.Code.CANCELLED) {
				throw e;
			}
			log.info("gRPC call cancelled while answering; dropping the response");
		}
	}

	private static ChainProduct toProto(ChainProductService.ChainProduct product) {
		ChainProduct.Builder proto = ChainProduct.newBuilder()
				.setItemId(product.itemId())
				.setName(product.name())
				.setBrand(product.brand() == null ? "" : product.brand())
				.setPriceAmount(product.priceAmount().doubleValue())
				.setCurrency(product.currency() == null ? "" : product.currency())
				.setPromoFlag(product.promoFlag())
				.setPackSize(product.packSize() == null ? "" : product.packSize());
		OffsetDateTime capturedAt = product.capturedAt();
		if (capturedAt != null) {
			proto.setCapturedAt(Timestamp.newBuilder().setSeconds(capturedAt.toEpochSecond()).setNanos(capturedAt.getNano()));
		}
		return proto.build();
	}
}
