package com.stocker.pricing.service.match;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.refresh.OnDemandRefreshService;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemMatchService.MatchResult;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The request path of a price lookup costs no database round trip (each is about 190 ms from New Zealand to a US
 * Neon region): everything is answered from the in-memory snapshot, and storing the matched copies under the caller's
 * item id happens afterwards on a background thread.
 */
class ItemMatchRequestPathBudgetTest {

	private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

	private PriceRecordRepository priceRecords;
	private NormalizedProductRepository normalizedRepository;
	private PriceStatsService stats;
	private EventPublisher events;
	private ItemMatchService service;
	private final List<Runnable> backgroundWork = new ArrayList<>();
	private NormalizedProductService normalizedService;

	private static NormalizedProduct milk(String chain, String price, String brand) {
		return NormalizedProduct.builder().itemId(chain + ":milk-2l").storeId(chain + ":").chainId(chain).canonicalKey("milk")
				.normalizedName("anchor blue top milk 2l").displayName("Anchor Blue Top Milk 2l").brand(brand)
				.packQuantity(new BigDecimal("2000")).packUnit("ml").priceAmount(new BigDecimal(price)).unitPrice(new BigDecimal("2.00"))
				.currency("NZD").capturedAt(NOW.minusHours(2)).build();
	}

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		priceRecords = mock(PriceRecordRepository.class);
		when(priceRecords.save(any(PriceRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
		normalizedRepository = mock(NormalizedProductRepository.class);
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				milk("newworld", "4.80", "Anchor"), milk("paknsave", "4.20", null), milk("woolworths", "4.50", "Anchor")));
		stats = mock(PriceStatsService.class);
		events = mock(EventPublisher.class);
		PricingCacheProperties properties = new PricingCacheProperties();
		NormalizedProductSnapshot snapshot = new NormalizedProductSnapshot(normalizedRepository, properties);
		snapshot.refresh();                                  // what the background refresh does at startup
		OnDemandRefreshService refresh = mock(OnDemandRefreshService.class);
		when(refresh.ensureFresh(any(), any())).thenReturn(new OnDemandRefreshService.Outcome(false, false));
		when(refresh.ensureForTerm(any(), any(), any(), any())).thenReturn(new OnDemandRefreshService.Outcome(false, false));
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		normalizedService = mock(NormalizedProductService.class);
		service = new ItemMatchService(priceRecords, snapshot, normalizedService,
				CommonProductCatalog.loadDefault(), refresh, stats, events, properties, provider);
		service.setPersistExecutor(backgroundWork::add);     // capture, run on demand
	}

	@Test
	void aWarmLookupMakesNoDatabaseCallOnTheRequestPath() {
		Mockito.clearInvocations(normalizedRepository);

		MatchResult result = service.match("full cream milk 2L", "list-item-1");

		assertThat(result.matches()).hasSize(3);
		assertThat(result.matches().get(0).chainId()).isEqualTo("PAKNSAVE");     // cheapest first
		verifyNoInteractions(normalizedRepository);
		verifyNoInteractions(priceRecords);
		verifyNoInteractions(stats);
		verifyNoInteractions(events);
	}

	@Test
	void theMatchedCopiesAreStoredAfterwardsWithoutLookingTheSourcesUp() {
		service.match("full cream milk 2L", "list-item-1");
		assertThat(backgroundWork).hasSize(1);

		backgroundWork.forEach(Runnable::run);

		ArgumentCaptor<PriceRecord> saved = ArgumentCaptor.forClass(PriceRecord.class);
		verify(priceRecords, times(3)).save(saved.capture());
		assertThat(saved.getAllValues()).allSatisfy(record -> {
			assertThat(record.getItemId()).isEqualTo("list-item-1");
			assertThat(record.getChannel()).isEqualTo("pickup");
			assertThat(record.getCurrency()).isEqualTo("NZD");
			assertThat(record.getRawAttributes()).containsEntry("name", "Anchor Blue Top Milk 2l");
		});
		assertThat(saved.getAllValues()).extracting(PriceRecord::getChainId)
				.containsExactlyInAnyOrder("newworld", "paknsave", "woolworths");
		assertThat(saved.getAllValues()).filteredOn(record -> record.getChainId().equals("paknsave")).singleElement()
				.satisfies(record -> assertThat(record.getPriceAmount()).isEqualByComparingTo("4.20"));
		verify(priceRecords, never()).findByItemIdAndStoreId(any(), any());     // no per-chain source lookups
		verify(events, times(3)).publishPriceRecordCaptured(any());
		verify(stats, times(3)).recordObservation(any());
	}

	@Test
	void storingTheSameObservationTwiceDoesNotDuplicateIt() {
		service.match("full cream milk 2L", "list-item-1");
		service.match("full cream milk 2L", "list-item-1");
		when(priceRecords.findByItemId("list-item-1")).thenReturn(List.of(), List.of(
				PriceRecord.builder().itemId("list-item-1").chainId("newworld").storeId("newworld:").capturedAt(NOW.minusHours(2)).build(),
				PriceRecord.builder().itemId("list-item-1").chainId("paknsave").storeId("paknsave:").capturedAt(NOW.minusHours(2)).build(),
				PriceRecord.builder().itemId("list-item-1").chainId("woolworths").storeId("woolworths:").capturedAt(NOW.minusHours(2)).build()));

		backgroundWork.forEach(Runnable::run);

		verify(priceRecords, times(3)).save(any(PriceRecord.class));   // only the first run stored anything
	}

	@Test
	void aFailingBackgroundStoreNeverBreaksTheLookup() {
		when(priceRecords.findByItemId(any())).thenThrow(new IllegalStateException("neon is down"));
		MatchResult result = service.match("full cream milk 2L", "list-item-1");

		backgroundWork.forEach(Runnable::run);   // logged, not thrown

		assertThat(result.matches()).hasSize(3);
	}

	@Test
	void withoutAnItemIdNothingIsStored() {
		service.match("full cream milk 2L", "");

		assertThat(backgroundWork).isEmpty();
	}

	@Test
	void aSearchThatMatchesNothingIsRecordedInTheBackgroundNotOnTheRequestPath() {
		MatchResult result = service.match("dragonfruit liqueur", "list-item-2");

		assertThat(result.matches()).isEmpty();
		verifyNoInteractions(normalizedService);        // no database write while the user waits
		backgroundWork.forEach(Runnable::run);
		verify(normalizedService).recordUnmatched("dragonfruit liqueur");
	}

}
