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
		properties.getSnapshot().setTtl(java.time.Duration.ZERO);   // pass straight through to the stubbed repository
		service = new ItemMatchService(repository, new com.stocker.pricing.service.normalize.NormalizedProductSnapshot(normalizedRepository, properties), normalizedService, catalog, refreshService, stats, events, properties, provider);
		service.setPersistExecutor(Runnable::run);   // store synchronously so the assertions can see it
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

	// ---- search relevance: dishes, qualifiers, sizes, branded items (fixtures: no carton eggs / biscuits are crawled yet) ----

	/** A canonical-index row whose pack size and unit price come from the name, like the real normaliser. */
	private NormalizedProduct canon(String key, String chain, String itemId, String name, String price) {
		com.stocker.pricing.normalize.PackSize size = com.stocker.pricing.normalize.ProductNormalizer.sizeOf(name);
		BigDecimal amount = new BigDecimal(price);
		return NormalizedProduct.builder().itemId(itemId).storeId(chain + ":").chainId(chain).canonicalKey(key)
				.normalizedName(name.toLowerCase()).displayName(name)
				.packUnit(size == null ? null : size.unit().code()).packQuantity(size == null ? null : size.quantity())
				.unitPrice(size == null ? null : size.unitPrice(amount)).priceAmount(amount).currency("NZD")
				.capturedAt(NOW).updatedAt(NOW).build();
	}

	private void indexed(String key, NormalizedProduct... rows) {
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq(key), any()))
				.thenReturn(List.of(rows));
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(rows));
	}

	private static List<String> names(List<ItemMatchService.MatchedProduct> products) {
		return products.stream().map(ItemMatchService.MatchedProduct::productName).toList();
	}

	@Test
	void aDishThatMerelyContainsTheWordEggIsNeverAnEggMatch() {
		indexed("eggs",
				canon("eggs", "woolworths", "woolworths:1", "Ben's Original Microwave Rice Egg Fried Rice 250g Pouch", "4.50"),
				canon("eggs", "newworld", "newworld:1", "Pams Free Range Eggs Size 7 12 Pack", "7.50"));

		for (String term : List.of("eggs", "eggs size 7")) {
			MatchResult result = service.match(term, "item-" + term);
			assertEquals(List.of("Pams Free Range Eggs Size 7 12 Pack"), names(result.matches()), term);
			assertTrue(names(result.alternatives()).stream().noneMatch(n -> n.contains("Fried Rice")), term);
		}
	}

	@Test
	void withOnlyADishStoredEggsFindNothingOnBothPaths() {
		NormalizedProduct dish = canon("eggs", "woolworths", "woolworths:1",
				"Ben's Original Microwave Rice Egg Fried Rice 250g Pouch", "4.50");
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq("eggs"), any()))
				.thenReturn(List.of());
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(dish));

		MatchResult result = service.match("eggs", "item-1");

		assertTrue(result.matches().isEmpty());
		assertTrue(result.alternatives().isEmpty());
	}

	@Test
	void theLexicalFallbackAlsoRejectsDishesAndKeepsRealEggs() {
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq("eggs"), any()))
				.thenReturn(List.of());
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("woolworths", "woolworths:1", "Egg Fried Rice 250g", null, "4.50", NOW),
				row("woolworths", "woolworths:2", "Egg Mayonnaise 400g", null, "4.00", NOW),
				row("woolworths", "woolworths:3", "Chocolate Easter Egg 100g", null, "3.00", NOW),
				row("newworld", "newworld:3", "Eggs Cage Free 6 Pack", null, "5.00", NOW)));

		MatchResult result = service.match("eggs", "item-1");

		assertEquals("lexical", result.matchMethod());
		assertEquals(List.of("Eggs Cage Free 6 Pack"), names(result.matches()));
		assertTrue(result.alternatives().isEmpty(), names(result.alternatives()).toString());
	}

	@Test
	void eggGradeAndPackCountQualifiersKeepOnlyThatEgg() {
		indexed("eggs",
				canon("eggs", "newworld", "newworld:1", "Pams Free Range Eggs Size 7 12 Pack", "7.50"),
				canon("eggs", "newworld", "newworld:2", "Farm Fresh Barn Eggs Size 6 12pk", "6.00"),
				canon("eggs", "newworld", "newworld:3", "Eggs Cage Free 6 Pack", "4.00"),
				canon("eggs", "paknsave", "paknsave:1", "Farm Fresh Barn Eggs Size 6 12pk", "5.90"),
				canon("eggs", "woolworths", "woolworths:1", "Woolworths Free Range Eggs Size 7 12 Pack", "7.20"));

		assertEquals(List.of("Woolworths Free Range Eggs Size 7 12 Pack", "Pams Free Range Eggs Size 7 12 Pack"),
				names(service.match("eggs size 7", "a").matches()));
		assertEquals(List.of("Farm Fresh Barn Eggs Size 6 12pk", "Farm Fresh Barn Eggs Size 6 12pk"),
				names(service.match("eggs s6", "b").matches()));
		assertEquals(List.of("Eggs Cage Free 6 Pack"), names(service.match("eggs 6 pack", "c").matches()));
		assertEquals(List.of("Pams Free Range Eggs Size 7 12 Pack"),
				service.match("size 7 eggs 12 pack", "d").matches().stream().filter(m -> m.chainId().equals("NEWWORLD"))
						.map(ItemMatchService.MatchedProduct::productName).toList());
	}

	@Test
	void aSizedMilkSearchDiffersFromAPlainOneAndNeverFallsBackToTheTypicalSize() {
		indexed("milk",
				canon("milk", "newworld", "newworld:1", "Anchor Blue Top Milk 1L", "1.80"),
				canon("milk", "newworld", "newworld:2", "Anchor Blue Top Milk 2L", "3.90"),
				canon("milk", "newworld", "newworld:3", "Anchor Blue Top Milk 3L", "6.60"));

		MatchResult plain = service.match("milk", "item-1");
		MatchResult sized = service.match("milk 2L", "item-2");

		assertEquals(List.of("Anchor Blue Top Milk 1L"), names(plain.matches()), "all sizes, cheapest per litre");
		assertEquals(List.of("Anchor Blue Top Milk 2L"), names(sized.matches()));
		assertEquals(List.of("Anchor Blue Top Milk 2L"), names(service.match("milk 2 litres", "item-3").matches()));
	}

	@Test
	void timTamsAreFoundByEveryWritingAtAnyChainViaTheCanonicalPath() {
		indexed("tim-tams",
				canon("tim-tams", "newworld", "newworld:1", "Arnott's Tim Tam Original 200g", "3.99"),
				canon("tim-tams", "paknsave", "paknsave:1", "Tim Tam Double Coat 200g", "3.79"),
				canon("tim-tams", "woolworths", "woolworths:1", "Arnott's TimTam Original 200g", "4.20"));

		for (String term : List.of("tim tam", "tim tams", "timtam", "Tim Tams 200g")) {
			MatchResult result = service.match(term, "item-" + term);
			assertEquals(3, result.matches().size(), term);
			assertEquals("canonical", result.matchMethod(), term);
		}
	}

	@Test
	void timTamsAreFoundByTheLexicalFallbackWhenNothingIsIndexedUnderTheKeyYet() {
		when(normalizedRepository.findByCanonicalKeyAndCapturedAtAfter(org.mockito.ArgumentMatchers.eq("tim-tams"), any()))
				.thenReturn(List.of());
		when(normalizedRepository.findByCapturedAtAfter(any())).thenReturn(List.of(
				row("newworld", "newworld:1", "Arnott's Tim Tam Original 200g", null, "3.99", NOW),
				row("paknsave", "paknsave:1", "Tim Tam Double Coat 200g", null, "3.79", NOW)));

		for (String term : List.of("tim tam", "tim tams", "timtam")) {
			assertEquals(2, service.match(term, "item-" + term).matches().size(), term);
		}
	}

	@Test
	void aChocolateBlockCalledDairyMilkIsNotDroppedByTheMilkExclude() {
		indexed("chocolate", canon("chocolate", "newworld", "newworld:1", "Cadbury Roses Milk Chocolate Block 180g", "4.50"));

		assertEquals(1, service.match("chocolate", "item-1").matches().size());
	}

	@Test
	void productsOfOtherSizesAreKeptInternallyNotAsMatches() {
		indexed("milk",
				canon("milk", "newworld", "newworld:1", "Anchor Blue Top Milk 1L", "1.80"),
				canon("milk", "newworld", "newworld:2", "Anchor Blue Top Milk 2L", "3.90"),
				canon("milk", "paknsave", "paknsave:1", "Pams Standard Milk 3L", "5.50"));

		ItemMatchService.MatchOutcome sized = service.matchDetailed("milk 2L", "item-1", null, null, null);
		ItemMatchService.MatchOutcome plain = service.matchDetailed("milk", "item-2", null, null, null);

		assertEquals(List.of("Anchor Blue Top Milk 2L"), names(sized.result().matches()));
		assertEquals(List.of("Anchor Blue Top Milk 1L", "Pams Standard Milk 3L"), names(sized.otherSizes()));
		assertTrue(plain.otherSizes().isEmpty(), "no qualifier asked, so no product is 'another size'");
		assertEquals(2, plain.result().matches().size());
	}

	@Test
	void aPantrySizeHintBehavesLikeATypedSize() {
		indexed("milk",
				canon("milk", "newworld", "newworld:1", "Anchor Blue Top Milk 1L", "1.80"),
				canon("milk", "newworld", "newworld:2", "Anchor Blue Top Milk 2L", "3.90"));

		assertEquals(List.of("Anchor Blue Top Milk 2L"),
				names(service.match("milk", "item-1", null, new BigDecimal("2"), null).matches()));
	}
}
