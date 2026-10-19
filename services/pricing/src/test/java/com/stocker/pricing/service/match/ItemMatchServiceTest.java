package com.stocker.pricing.service.match;

import com.stocker.pricing.domain.port.EventPublisher;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.normalize.NormalizedProductService;
import com.stocker.pricing.service.PriceStatsService;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.match.ItemMatchService.MatchResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemMatchServiceTest {

	private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

	private PriceRecordRepository repository;
	private PriceStatsService stats;
	private EventPublisher events;
	private IngestScheduler ingest;
	private ItemMatchService service;
	private NormalizedProductRepository normalizedRepository;
	private NormalizedProductService normalizedService;
	private com.stocker.pricing.refresh.OnDemandRefreshService refreshService;
	private final CommonProductCatalog catalog = CommonProductCatalog.loadDefault();
	private final PricingCacheProperties properties = new PricingCacheProperties();
	private final Map<String, PriceRecord> sources = new java.util.HashMap<>();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		repository = mock(PriceRecordRepository.class);
		stats = mock(PriceStatsService.class);
		events = mock(EventPublisher.class);
		ingest = mock(IngestScheduler.class);
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(ingest);
		when(repository.save(any(PriceRecord.class))).thenAnswer(inv -> inv.getArgument(0));
		normalizedRepository = mock(NormalizedProductRepository.class);
		normalizedService = mock(NormalizedProductService.class);
		refreshService = mock(com.stocker.pricing.refresh.OnDemandRefreshService.class);
		when(refreshService.ensureFresh(any(), any()))
				.thenReturn(new com.stocker.pricing.refresh.OnDemandRefreshService.Outcome(false, false));
		when(refreshService.ensureForTerm(any(), any(), any(), any()))
				.thenReturn(new com.stocker.pricing.refresh.OnDemandRefreshService.Outcome(false, false));
		when(repository.findByItemIdAndStoreId(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(inv -> {
					PriceRecord source = sources.get(inv.getArgument(0) + "|" + inv.getArgument(1));
					return source == null ? List.of() : List.of(source);
				});
		service = new ItemMatchService(repository, normalizedRepository, normalizedService, catalog, refreshService, stats, events, properties, provider);
	}

	@Test
	void picksTheBestProductPerChainCheapestFirstAndLabelsTheRest() {
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW),
				row("newworld", "newworld:2", "Anchor Trim Milk 1L", "Anchor", "2.60", NOW),
				row("paknsave", "paknsave:1", "Pams Standard Milk 2L", "Pams", "3.90", NOW),
				row("woolworths", "woolworths:1", "Woolworths Full Cream Milk 2L", null, "4.10", NOW),
				row("paknsave", "paknsave:9", "Oat Drink 2L", null, "5.00", NOW)));

		MatchResult result = service.match("milk 2L", "list-item-1");

		assertEquals("lexical", result.matchMethod());
		assertEquals(List.of("PAKNSAVE", "WOOLWORTHS", "NEWWORLD"),
				result.matches().stream().map(m -> m.chainId()).toList());
		assertEquals("Anchor", result.matches().get(2).brand());
		assertEquals(1, result.alternatives().size(), "only the other-size New World milk; oat drink shares no word");
		assertEquals("Anchor Trim Milk 1L", result.alternatives().get(0).productName());
	}

	@Test
	void onlyTheChosenProductsAreStoredUnderTheCallersItemIdAndFeedTheStats() {
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW),
				row("newworld", "newworld:2", "Anchor Trim Milk 1L", "Anchor", "2.60", NOW)));

		service.match("milk 2L", "list-item-1");

		ArgumentCaptor<PriceRecord> saved = ArgumentCaptor.forClass(PriceRecord.class);
		verify(repository, times(1)).save(saved.capture());
		assertEquals("list-item-1", saved.getValue().getItemId());
		assertEquals(new BigDecimal("4.80"), saved.getValue().getPriceAmount());
		verify(stats, times(1)).recordObservation(any());
		verify(events, times(1)).publishPriceRecordCaptured(any());
	}

	@Test
	void askingAgainDoesNotStoreTheSameObservationTwice() {
		NormalizedProduct source = row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW);
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(source));
		when(repository.findByItemId("list-item-1")).thenReturn(List.of(PriceRecord.builder()
				.itemId("list-item-1").chainId("newworld").storeId(source.getStoreId()).capturedAt(NOW).build()));

		service.match("milk 2L", "list-item-1");

		verify(repository, never()).save(any(PriceRecord.class));
	}

	@Test
	void aPackSizeScrapedIntoTheBrandSlotCountsAsPartOfTheName() {
		// New World / PAK'nSave: the card subtitle ("2l") is stored as the brand.
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Milk", "1l", "1.82", NOW),
				row("newworld", "newworld:2", "Anchor Blue Milk", "2l", "3.73", NOW)));

		MatchResult result = service.match("milk 2L", "list-item-1");

		assertEquals(1, result.matches().size());
		assertEquals("Anchor Blue Milk 2l", result.matches().get(0).productName());
		assertEquals(null, result.matches().get(0).brand(), "a size is not a brand");
		assertEquals(List.of("Anchor Blue Milk 1l"), result.alternatives().stream().map(a -> a.productName()).toList());
	}

	@Test
	void usesTheLatestScrapeOfEachProduct() {
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "5.50", NOW.minusDays(1)),
				row("newworld", "newworld:1", "Anchor Blue Top Milk 2L", "Anchor", "4.80", NOW)));

		MatchResult result = service.match("milk 2L", "list-item-1");

		assertEquals(1, result.matches().size());
		assertEquals(new BigDecimal("4.80"), result.matches().get(0).priceAmount());
	}

	@Test
	void ignoresUnknownChains() {
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("spread-thing", "spread-thing:1", "Milk 2L", null, "3.00", NOW)));

		assertTrue(service.match("milk 2L", "list-item-1").matches().isEmpty());
	}

	@Test
	void withOnDemandIngestEnabledNothingStoredStartsOneBackgroundRunAndReturnsEmptyNotAnError() {
		properties.getMatch().setOnDemandIngest(true);
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of());

		MatchResult first = service.match("milk 2L", "list-item-1");
		service.match("milk 2L", "list-item-1");

		assertTrue(first.matches().isEmpty());
		assertTrue(first.alternatives().isEmpty());
		verify(ingest, timeout(2000).times(1)).runAll();
		verify(repository, never()).save(any(PriceRecord.class));
	}

	/** A stored product as the normalised index holds it, plus the price_records row it was derived from. */
	private NormalizedProduct row(String chain, String itemId, String name, String brand, String price, OffsetDateTime at) {
		boolean sizeLike = brand != null && brand.matches("(?i)\\d+(?:\\.\\d+)?\\s*(?:l|ml|g|kg)");
		String displayName = sizeLike ? name + " " + brand : name;
		com.stocker.pricing.normalize.PackSize size = com.stocker.pricing.normalize.ProductNormalizer.sizeOf(displayName);
		BigDecimal amount = new BigDecimal(price);
		sources.put(itemId + "|" + chain + ":", PriceRecord.builder().itemId(itemId).chainId(chain).storeId(chain + ":")
				.channel("pickup").priceAmount(amount).currency("NZD").capturedAt(at).createdAt(at)
				.rawAttributes(Map.of("name", name)).build());
		return NormalizedProduct.builder().itemId(itemId).storeId(chain + ":").chainId(chain)
				.normalizedName(displayName.toLowerCase()).displayName(displayName).brand(sizeLike ? null : brand)
				.packUnit(size == null ? null : size.unit().code()).packQuantity(size == null ? null : size.quantity())
				.unitPrice(size == null ? null : size.unitPrice(amount)).priceAmount(amount).currency("NZD")
				.capturedAt(at).updatedAt(at).build();
	}

	private static NormalizedProduct normalized(String chain, String itemId, String name, String price, String unit,
			String quantity, OffsetDateTime at) {
		return normalized("milk", chain, itemId, name, price, unit, quantity, at);
	}

	private static NormalizedProduct normalized(String key, String chain, String itemId, String name, String price,
			String unit, String quantity, OffsetDateTime at) {
		BigDecimal amount = new BigDecimal(price);
		BigDecimal qty = new BigDecimal(quantity);
		return NormalizedProduct.builder().itemId(itemId).storeId(chain + ":").chainId(chain).canonicalKey(key)
				.normalizedName(name.toLowerCase()).displayName(name).priceAmount(amount).currency("NZD")
				.packUnit(unit).packQuantity(qty).unitPrice(amount.divide(qty.divide(new BigDecimal(1000)), 4,
						java.math.RoundingMode.HALF_UP))
				.capturedAt(at).updatedAt(at).build();
	}

	@Test
	void theStaleFlagFromTheFreshnessCheckReachesTheResult() {
		when(refreshService.ensureFresh(any(), any()))
				.thenReturn(new com.stocker.pricing.refresh.OnDemandRefreshService.Outcome(true, false));
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq("milk"), any()))
				.thenReturn(List.of(normalized("newworld", "newworld:1", "Anchor Blue Top Milk 2l", "3.70", "ml", "2000", NOW)));

		assertTrue(service.match("milk 2L", "item-1").stale());
	}

	@Test
	void aTermThatIsNotACommonProductNeverTriggersAFreshnessRefresh() {
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of());

		service.match("dragon scale elixir", "item-1");

		verify(refreshService, never()).ensureFresh(any(), any());
	}

	@Test
	void oldDataIsStillShownForAChainAndTheSameProductIsListedOnce() {
		OffsetDateTime old = NOW.minusDays(25);
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq("milk"), any()))
				.thenReturn(List.of(
						normalized("newworld", "newworld:1", "Anchor Blue Top Milk 2l", "3.70", "ml", "2000", old),
						normalized("woolworths", "woolworths:1", "Fresha Valley Milk 2L", "5.14", "ml", "2000", NOW),
						normalized("woolworths", "woolworths:2", "Fresha Valley Milk 2L", "5.14", "ml", "2000", NOW.minusHours(1)),
						normalized("woolworths", "woolworths:3", "Fresha Valley Milk 2L", "5.14", "ml", "2000", NOW.minusHours(2))));

		MatchResult result = service.match("milk", "item-1");

		assertEquals(List.of("NEWWORLD", "WOOLWORTHS"), result.matches().stream().map(m -> m.chainId()).sorted().toList());
		assertTrue(result.alternatives().isEmpty(), "the same Woolworths product must not come back as an alternative");
	}

	@Test
	void aPlainSearchPrefersTheStandardProductOverALiteVariantUnlessAsked() {
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq("milk"), any()))
				.thenReturn(List.of(
						normalized("newworld", "newworld:1", "Pams Value Lite Milk 2l", "4.50", "ml", "2000", NOW),
						normalized("newworld", "newworld:2", "Pams Value Standard Milk 2l", "4.72", "ml", "2000", NOW)));

		assertEquals("Pams Value Standard Milk 2l", service.match("milk", "item-1").matches().get(0).productName());
		assertEquals("Pams Value Lite Milk 2l", service.match("lite milk", "item-2").matches().get(0).productName());
	}

	@Test
	void anyOtherItemIsScoredAgainstEverythingStoredAndFreshlyRoutedPagesAreRescored() {
		NormalizedProduct fresh = row("paknsave", "paknsave:77", "Value Sriracha Hot Chilli Sauce 440g", null, "3.20", NOW);
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of()).thenReturn(List.of(fresh));
		when(refreshService.ensureForTerm(org.mockito.ArgumentMatchers.eq("sriracha hot chilli sauce"), any(), any(), any()))
				.thenReturn(new com.stocker.pricing.refresh.OnDemandRefreshService.Outcome(false, true));

		MatchResult result = service.match("sriracha hot chilli sauce", "item-9");

		assertEquals(1, result.matches().size());
		assertEquals("PAKNSAVE", result.matches().get(0).chainId());
		verify(refreshService, times(1)).ensureForTerm(any(), any(), any(), any());
	}

	@Test
	void theCategoryHintIsPassedToTheRoutedRefresh() {
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of());

		service.match("mystery paste", "item-9", null, null, "condiments");

		verify(refreshService).ensureForTerm(org.mockito.ArgumentMatchers.eq("mystery paste"),
				org.mockito.ArgumentMatchers.eq("condiments"), any(), any());
	}

	@Test
	void aStaleChosenMatchMakesThatChainRefreshEligibleAndFlagsStale() {
		NormalizedProduct old = row("newworld", "newworld:5", "Value Sriracha Hot Chilli Sauce 440g", null, "3.20", NOW.minusDays(3));
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(old));

		MatchResult result = service.match("sriracha hot chilli sauce", "item-9");

		assertTrue(result.stale());
		assertEquals(1, result.matches().size(), "old data is still shown, flagged");
	}
}
