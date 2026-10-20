package com.stocker.pricing.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.normalize.CommonProduct;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class OnDemandRefreshServiceTest {

	private static final String MILK_NW = "https://www.newworld.co.nz/shop/category/fridge-deli-and-eggs/milk/fresh-milk?pg=1";
	private static final String MILK_WW = "https://www.woolworths.co.nz/shop/browse/fridge-deli/milk/full-cream-milk";

	private NormalizedProductRepository repository;
	private IngestScheduler scheduler;
	private PricingCacheProperties properties;
	private OnDemandRefreshService service;
	private final CommonProduct milk = CommonProductCatalog.loadDefault().byKey("milk").orElseThrow();

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		scheduler = mock(IngestScheduler.class);
		ObjectProvider<IngestScheduler> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(scheduler);
		properties = new PricingCacheProperties();
		// These tests cover the opt-in waiting mode (deadline > 0); the default never waits: OnDemandRefreshNonBlockingTest.
		properties.getOnDemand().setDeadline(java.time.Duration.ofSeconds(6));
		service = new OnDemandRefreshService(passThrough(repository, properties), CategoryMap.loadDefault(), CategoryRouter.loadDefault(), properties, provider);
	}

	private static Object[] newest(String chain, OffsetDateTime at) {
		return new Object[] { chain, at };
	}

	private static OffsetDateTime hoursAgo(long hours) {
		return OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours);
	}

	@Test
	void freshDataMakesNoRequestAtAll() {
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(1)), newest("paknsave", hoursAgo(1)), newest("woolworths", hoursAgo(1))));

		OnDemandRefreshService.Outcome outcome = service.ensureFresh(milk, () -> { });

		assertFalse(outcome.stale());
		assertFalse(outcome.refreshed());
		verify(scheduler, never()).refreshCategories(any(), anyList(), any());
	}

	@Test
	void onlyTheStaleChainsPageIsLoaded() {
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(30)), newest("paknsave", hoursAgo(1)), newest("woolworths", hoursAgo(1))));

		OnDemandRefreshService.Outcome outcome = service.ensureFresh(milk, () -> { });

		assertTrue(outcome.refreshed());
		verify(scheduler, times(1)).refreshCategories(eq(ChainId.NEWWORLD), eq(List.of(MILK_NW)), any());
		verify(scheduler, never()).refreshCategories(eq(ChainId.PAKNSAVE), anyList(), any());
		verify(scheduler, never()).refreshCategories(eq(ChainId.WOOLWORTHS), anyList(), any());
	}

	@Test
	void aChainWithNoDataAtAllIsFetchedButNotCalledStale() {
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(1)), newest("paknsave", hoursAgo(1))));

		OnDemandRefreshService.Outcome outcome = service.ensureFresh(milk, () -> { });

		verify(scheduler).refreshCategories(eq(ChainId.WOOLWORTHS), eq(List.of(MILK_WW)), any());
		assertFalse(outcome.stale(), "a chain without rows is unavailable, not stale");
	}

	@Test
	void theSamePageIsNotRequestedAgainWithinTheCooldown() {
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(30)), newest("paknsave", hoursAgo(1)), newest("woolworths", hoursAgo(1))));

		service.ensureFresh(milk, () -> { });
		OnDemandRefreshService.Outcome second = service.ensureFresh(milk, () -> { });

		verify(scheduler, times(1)).refreshCategories(eq(ChainId.NEWWORLD), anyList(), any());
		assertTrue(second.stale(), "still old data, but we do not hammer the site");
		assertFalse(second.refreshed());
	}

	@Test
	void theHourlyCapPerChainIsEnforced() {
		properties.getOnDemand().setMaxPageLoadsPerChainPerHour(1);
		properties.getOnDemand().setCategoryCooldown(Duration.ZERO);
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(30)), newest("paknsave", hoursAgo(1)), newest("woolworths", hoursAgo(1))));

		service.ensureFresh(milk, () -> { });
		service.ensureFresh(milk, () -> { });

		verify(scheduler, times(1)).refreshCategories(eq(ChainId.NEWWORLD), anyList(), any());
	}

	@Test
	void disabledMeansNoRequestsButStillReportsStale() {
		properties.getOnDemand().setEnabled(false);
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(30)), newest("paknsave", hoursAgo(1)), newest("woolworths", hoursAgo(1))));

		OnDemandRefreshService.Outcome outcome = service.ensureFresh(milk, () -> { });

		assertTrue(outcome.stale());
		verify(scheduler, never()).refreshCategories(any(), anyList(), any());
	}

	@Test
	void aSlowRefreshAnswersWithStaleDataAndFinishesInTheBackground() throws Exception {
		properties.getOnDemand().setDeadline(Duration.ofMillis(100));
		when(repository.newestByChain("milk")).thenReturn(List.of(
				newest("newworld", hoursAgo(30)), newest("paknsave", hoursAgo(1)), newest("woolworths", hoursAgo(1))));
		when(scheduler.refreshCategories(eq(ChainId.NEWWORLD), anyList(), any())).thenAnswer(invocation -> {
			Thread.sleep(600);
			return 5;
		});
		AtomicInteger followUps = new AtomicInteger();

		OnDemandRefreshService.Outcome outcome = service.ensureFresh(milk, followUps::incrementAndGet);

		assertTrue(outcome.stale());
		assertFalse(outcome.refreshed());
		long deadline = System.currentTimeMillis() + 3000;
		while (followUps.get() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertEquals(1, followUps.get(), "the follow-up runs once the late refresh completes");
	}

	@Test
	void everyCategoryInTheMapPointsAtARealChainUrlShape() {
		CategoryMap map = CategoryMap.loadDefault();
		assertTrue(map.size() > 180);
		assertTrue(map.url("milk", ChainId.NEWWORLD).orElseThrow().startsWith("https://www.newworld.co.nz/shop/category/"));
		assertTrue(map.url("milk", ChainId.PAKNSAVE).orElseThrow().startsWith("https://www.paknsave.co.nz/shop/category/"));
		assertTrue(map.url("milk", ChainId.WOOLWORTHS).orElseThrow().startsWith("https://www.woolworths.co.nz/shop/browse/"));
		assertTrue(map.url("celery", ChainId.NEWWORLD).isPresent());   // shares the salad-greens page (no dedicated one)
		assertTrue(map.url("not-a-product", ChainId.NEWWORLD).isEmpty());
		List<String> unused = new ArrayList<>();
		CommonProductCatalog catalog = CommonProductCatalog.loadDefault();
		for (String key : new String[] { "milk", "eggs", "beef-mince", "bananas" }) {
			assertTrue(catalog.byKey(key).isPresent(), key);
		}
		assertTrue(unused.isEmpty());
	}

	/** Zero ttl: the snapshot passes straight through to the stubbed repository. */
	private static com.stocker.pricing.service.normalize.NormalizedProductSnapshot passThrough(
			com.stocker.pricing.repository.NormalizedProductRepository repository, PricingCacheProperties properties) {
		properties.getSnapshot().setTtl(java.time.Duration.ZERO);
		return new com.stocker.pricing.service.normalize.NormalizedProductSnapshot(repository, properties);
	}
}
