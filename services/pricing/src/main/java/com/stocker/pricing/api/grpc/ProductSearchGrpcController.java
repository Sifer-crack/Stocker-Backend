package com.stocker.pricing.api.grpc;

import com.google.protobuf.Timestamp;
import com.stocker.pricing.api.grpc.v1.ChainPrice;
import com.stocker.pricing.api.grpc.v1.ParsedQuery;
import com.stocker.pricing.api.grpc.v1.Product;
import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import com.stocker.pricing.api.grpc.v1.SearchProductsRequest;
import com.stocker.pricing.api.grpc.v1.SearchProductsResponse;
import com.stocker.pricing.api.grpc.v1.SelectProductRequest;
import com.stocker.pricing.api.grpc.v1.SelectProductResponse;
import com.stocker.pricing.service.search.ProductSearchService;
import com.stocker.pricing.service.search.ProductSearchService.ProductNotFoundException;
import com.stocker.pricing.service.search.ProductSearchService.SearchResult;
import com.stocker.pricing.service.search.ProductSearchService.SelectedProduct;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.util.StringUtils;

/**
 * gRPC side of the merged-product search (docs/features/price-search-v2.md). Cancellation follows
 * {@link PriceRecordGrpcController}: check at the start and before replying, never write to a cancelled call.
 */
@GrpcService
public class ProductSearchGrpcController extends ProductSearchGrpc.ProductSearchImplBase {

	private static final Logger log = LoggerFactory.getLogger(ProductSearchGrpcController.class);

	private final ProductSearchService searchService;

	public ProductSearchGrpcController(ProductSearchService searchService) {
		this.searchService = searchService;
	}

	@Override
	public void searchProducts(SearchProductsRequest request, StreamObserver<SearchProductsResponse> responseObserver) {
		if (isCancelled(responseObserver)) {
			return;
		}
		log.info("gRPC searchProducts: term={}, itemId={}", request.getTerm(), request.getItemId());
		if (!StringUtils.hasText(request.getTerm()) || !StringUtils.hasText(request.getItemId())) {
			responseObserver.onError(
					Status.INVALID_ARGUMENT.withDescription("term and itemId are required").asRuntimeException());
			return;
		}
		SearchResult result = searchService.search(request.getTerm(), request.getItemId(), request.getCategory(),
				request.getLimit());
		if (isCancelled(responseObserver)) {
			return;
		}
		SearchProductsResponse.Builder response = SearchProductsResponse.newBuilder()
				.setQuery(toProto(result.query()))
				.setMatchMethod(result.matchMethod())
				.setStale(result.stale());
		result.products().forEach(product -> response.addProducts(toProto(product)));
		result.alternatives().forEach(product -> response.addAlternatives(toProto(product)));
		reply(responseObserver, response.build());
	}

	@Override
	public void selectProduct(SelectProductRequest request, StreamObserver<SelectProductResponse> responseObserver) {
		if (isCancelled(responseObserver)) {
			return;
		}
		log.info("gRPC selectProduct: itemId={}, productKey={}", request.getItemId(), request.getProductKey());
		if (!StringUtils.hasText(request.getItemId()) || !StringUtils.hasText(request.getProductKey())) {
			responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("itemId and productKey are required")
					.asRuntimeException());
			return;
		}
		SelectedProduct selected;
		try {
			selected = searchService.selectProduct(request.getItemId(), request.getProductKey());
		}
		catch (ProductNotFoundException e) {
			if (!isCancelled(responseObserver)) {
				responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
			}
			return;
		}
		reply(responseObserver, SelectProductResponse.newBuilder().setItemId(selected.itemId())
				.setProductKey(selected.productKey()).addAllChains(selected.chains()).build());
	}

	private static ParsedQuery toProto(ProductSearchService.Query query) {
		ParsedQuery.Builder builder = ParsedQuery.newBuilder().setTerm(query.term()).addAllWords(query.words());
		if (query.size() != null) {
			builder.setSize(query.size());
		}
		if (query.packCount() != null) {
			builder.setPackCount(query.packCount());
		}
		if (query.grade() != null) {
			builder.setGrade(query.grade());
		}
		if (query.category() != null) {
			builder.setCategory(query.category());
		}
		return builder.build();
	}

	private static Product toProto(ProductSearchService.Product product) {
		Product.Builder builder = Product.newBuilder()
				.setProductKey(product.productKey())
				.setName(product.name())
				.setCheapestChainId(product.cheapestChainId());
		if (product.brand() != null) {
			builder.setBrand(product.brand());
		}
		if (product.category() != null) {
			builder.setCategory(product.category());
		}
		if (product.packSize() != null) {
			builder.setPackSize(product.packSize());
		}
		if (product.grade() != null) {
			builder.setGrade(product.grade());
		}
		if (product.unit() != null) {
			builder.setUnit(product.unit());
		}
		if (product.unitPrice() != null) {
			builder.setUnitPrice(product.unitPrice().doubleValue());
		}
		product.prices().forEach((chain, price) -> builder.putPrices(chain, toProto(price)));
		return builder.build();
	}

	private static ChainPrice toProto(ProductSearchService.ChainPrice price) {
		ChainPrice.Builder builder = ChainPrice.newBuilder()
				.setPrice(price.price().doubleValue())
				.setPromo(price.promo())
				.setStale(price.stale());
		if (price.unitPrice() != null) {
			builder.setUnitPrice(price.unitPrice().doubleValue());
		}
		if (price.storeId() != null) {
			builder.setStoreId(price.storeId());
		}
		if (price.capturedAt() != null) {
			builder.setCapturedAt(toTimestamp(price.capturedAt()));
		}
		return builder.build();
	}

	private static Timestamp toTimestamp(OffsetDateTime time) {
		return Timestamp.newBuilder().setSeconds(time.toEpochSecond()).setNanos(time.getNano()).build();
	}

	/** True once the client has gone (abort, disconnect or deadline); a cancelled call stops working. */
	private static boolean isCancelled(StreamObserver<?> responseObserver) {
		boolean cancelled = Context.current().isCancelled()
				|| (responseObserver instanceof ServerCallStreamObserver<?> call && call.isCancelled());
		if (cancelled) {
			log.info("gRPC call cancelled by the caller; skipping the remaining work");
		}
		return cancelled;
	}

	/** Sends the answer unless the call was cancelled meanwhile (a write to a cancelled call would only throw). */
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
}
