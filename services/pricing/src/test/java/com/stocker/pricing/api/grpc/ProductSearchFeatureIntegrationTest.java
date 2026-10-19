package com.stocker.pricing.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocker.pricing.api.grpc.v1.Product;
import com.stocker.pricing.api.grpc.v1.ProductSearchGrpc;
import com.stocker.pricing.api.grpc.v1.SearchProductsRequest;
import com.stocker.pricing.api.grpc.v1.SearchProductsResponse;
import com.stocker.pricing.api.grpc.v1.SelectProductRequest;
import com.stocker.pricing.api.grpc.v1.SelectProductResponse;
import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.PackSizeParser;
import com.stocker.pricing.refresh.OnDemandRefreshService;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemPriceStore;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import com.stocker.pricing.service.search.ProductSearchService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A real gRPC call into the real controller and the real search service; only the data (normalised products) and the
 * writers (price_records, stats, events, refresh) are faked.
 */
@Tag("feature-integration")
class ProductSearchFeatureIntegrationTest {

	private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);
	private static final CommonProductCatalog CATALOG = CommonProductCatalog.loadDefault();

	private final PriceRecordRepository priceRecords = mock(PriceRecordRepository.class);
	private Server server;
	private ManagedChannel channel;
	private ProductSearchGrpc.ProductSearchBlockingStub client;

	private static NormalizedProduct row(String chain, String name, String brand, String price) {
		BigDecimal amount = new BigDecimal(price);
		PackSize size = PackSizeParser.parse(name).orElse(null);
		return NormalizedProduct.builder().itemId(chain + ":" + name).storeId(chain + ":").chainId(chain)
				.canonicalKey(CATALOG.match(name).map(match -> match.key()).orElse(null)).normalizedName(name.toLowerCase())
				.displayName(name).brand(brand).packUnit(size == null ? null : size.unit().code())
				.packQuantity(size == null ? null : size.quantity()).priceAmount(amount).currency("NZD")
				.capturedAt(NOW).updatedAt(NOW).build();
	}

	@BeforeEach
	void start() throws Exception {
		List<NormalizedProduct> table = List.of(
				row("newworld", "Anchor Blue Top Milk 2l", "Anchor", "4.80"),
				row("paknsave", "Anchor Blue Top Fresh Milk 2 litre", "Anchor", "4.60"),
				row("woolworths", "Anchor Milk Blue Top 2L", null, "4.70"),
				row("newworld", "Anchor Blue Top Milk 1l", "Anchor", "2.60"),
				row("paknsave", "Pams Standard Milk 2l", "Pams", "3.90"));
		NormalizedProductRepository repository = mock(NormalizedProductRepository.class);
		when(repository.findByCapturedAtAfter(any())).thenReturn(table);
		when(repository.findByCanonicalKeyAndCapturedAtAfter(any(), any())).thenAnswer(inv -> table.stream()
				.filter(r -> inv.getArgument(0).equals(r.getCanonicalKey())).toList());
		when(priceRecords.save(any(PriceRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		OnDemandRefreshService refresh = mock(OnDemandRefreshService.class);
		when(refresh.ensureFresh(any(), any())).thenReturn(new OnDemandRefreshService.Outcome(false, false));
		PricingCacheProperties properties = new PricingCacheProperties();
		properties.getSnapshot().setTtl(Duration.ZERO);
		ProductSearchService service = new ProductSearchService(new NormalizedProductSnapshot(repository, properties),
				CATALOG, refresh, new ItemPriceStore(priceRecords, mock(PriceStatsService.class), mock(EventPublisher.class)),
				mock(NormalizedProductService.class), properties);
		server = ServerBuilder.forPort(0).addService(new ProductSearchGrpcController(service)).build().start();
		channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
		client = ProductSearchGrpc.newBlockingStub(channel);
	}

	@AfterEach
	void stop() throws Exception {
		channel.shutdownNow();
		server.shutdownNow();
		server.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void searchOverGrpcMergesChainsAndSelectStoresAnotherProduct() {
		SearchProductsResponse response = client.searchProducts(
				SearchProductsRequest.newBuilder().setTerm("milk 2L").setItemId("item-1").build());

		assertThat(response.getQuery().getSize()).isEqualTo("2L");
		assertThat(response.getProductsList()).extracting(Product::getProductKey)
				.containsExactly("pams-standard-milk|2000ml", "anchor-blue-top-milk|2000ml");
		Product anchor = response.getProducts(1);
		assertThat(anchor.getPricesMap()).containsOnlyKeys("newworld", "paknsave", "woolworths");
		assertThat(anchor.getCheapestChainId()).isEqualTo("paknsave");
		assertThat(response.getAlternativesList()).extracting(Product::getPackSize).containsExactly("1L");
		// the first product (Pams, one chain) was stored under the item id by the background writer; wait for it
		verify(priceRecords, org.mockito.Mockito.timeout(3000).times(1)).save(any(PriceRecord.class));

		SelectProductResponse selected = client.selectProduct(SelectProductRequest.newBuilder().setItemId("item-1")
				.setProductKey("anchor-blue-top-milk|2000ml").build());
		assertThat(selected.getChainsList()).containsExactlyInAnyOrder("newworld", "paknsave", "woolworths");
		verify(priceRecords, times(1 + 3)).save(any(PriceRecord.class));
	}

	@Test
	void errorsTravelAsGrpcStatusCodes() {
		assertThatThrownBy(() -> client.searchProducts(SearchProductsRequest.newBuilder().setTerm(" ").setItemId("i").build()))
				.isInstanceOfSatisfying(StatusRuntimeException.class,
						e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
		assertThatThrownBy(() -> client.selectProduct(SelectProductRequest.newBuilder().setItemId("i").setProductKey("nope|1l").build()))
				.isInstanceOfSatisfying(StatusRuntimeException.class,
						e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
	}
}
