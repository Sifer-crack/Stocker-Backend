package com.stocker.pricing.api.grpc;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainCatalogGrpc;
import com.stocker.pricing.api.grpc.v1.ChainProduct;
import com.stocker.pricing.api.grpc.v1.ListChainProductsRequest;
import com.stocker.pricing.api.grpc.v1.ListChainProductsResponse;
import com.stocker.pricing.service.ChainProductService;
import io.grpc.Status;
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
		log.info("gRPC listProducts: chain={}, query='{}', limit={}", request.getChainId(), request.getQuery(),
				request.getLimit());
		java.util.List<ChainProductService.ChainProduct> products;
		try {
			products = chainProductService.list(request.getChainId(), request.getQuery(), request.getLimit());
		}
		catch (IllegalArgumentException ex) {
			responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(ex.getMessage()).asRuntimeException());
			return;
		}
		ListChainProductsResponse.Builder response = ListChainProductsResponse.newBuilder();
		products.forEach(product -> response.addProducts(toProto(product)));
		responseObserver.onNext(response.build());
		responseObserver.onCompleted();
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
