package com.stocker.pricing.integration;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.ChainScraper;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.config.IngestProperties.ChainConfig;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@code ingest/IngestScheduler} - the batch-crawl pipeline that ties
 * {@code ChainScraper} results together with de-dupe, {@link com.stocker.pricing.service.RawProductPriceRecordMapper}
 * and {@code PriceRecordRepository} persistence. Unlike the {@code *PlaywrightIngestIntegrationTest}
 * and {@code MilkPriceComparisonLiveTest} classes in this package, this test needs no live browser,
 * network access or database - {@link Browser} and {@link ChainScraper} (the module's two external-system
 * boundaries) are stubbed, while the real {@link IngestScheduler}, its de-dupe logic and the real
 * {@code RawProductPriceRecordMapper} run unmodified, with persistence verified against a mocked
 * {@link PriceRecordRepository}. It therefore runs as part of the normal test suite, closing the gap
 * that none of the existing ingest tests exercise {@code IngestScheduler.runAll()} itself.
 */
@Tag("integration")
class IngestPipelineIntegrationTest {

	private Browser browser;
	private PriceRecordRepository priceRecordRepository;
	private IngestProperties properties;

	@BeforeEach
	void setUp() {
		browser = mock(Browser.class);
		priceRecordRepository = mock(PriceRecordRepository.class);
		when(priceRecordRepository.save(any(PriceRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

		properties = new IngestProperties();
		properties.setDryRun(false);
		properties.setMaxPagesPerCategory(5);
		properties.setNavigationTimeoutSeconds(30);
	}

	@Test
	void scrapesDedupesMapsAndPersistsAcrossEnabledChains() {
		enableChain(ChainId.NEWWORLD, "newworld-store", "New World Store", "https://www.newworld.co.nz/shop/category/milk");

		StubChainScraper newworld = StubChainScraper.thatReturns(ChainId.NEWWORLD, ScrapeResult.builder()
				.chainId(ChainId.NEWWORLD)
				.products(List.of(
						product("newworld", "P1", "Anchor Milk 2L", "3.79"),
						product("newworld", "P1", "Anchor Milk 2L (duplicate card)", "3.79"),
						product("newworld", "P2", "Meadow Fresh Milk 2L", "3.99")))
				.categoriesVisited(List.of("https://www.newworld.co.nz/shop/category/milk"))
				.zeroResultCategoryUrls(List.of())
				.warnings(List.of())
				.build());

		IngestScheduler scheduler = new IngestScheduler(List.of(newworld), browser, properties, priceRecordRepository);
		scheduler.runAll();

		assertEquals(1, newworld.invocations, "scraper should be invoked exactly once for its one configured chain");
		assertEquals(List.of("https://www.newworld.co.nz/shop/category/milk"), newworld.lastConfig.getCategoryUrls());
		assertEquals("newworld-store", newworld.lastConfig.getStoreId());
		assertEquals(5, newworld.lastConfig.getMaxPagesPerCategory());

		ArgumentCaptor<PriceRecord> captor = ArgumentCaptor.forClass(PriceRecord.class);
		verify(priceRecordRepository, times(2)).save(captor.capture());

		List<PriceRecord> saved = captor.getAllValues();
		assertEquals(List.of("newworld:P1", "newworld:P2"), saved.stream().map(PriceRecord::getItemId).toList(),
				"the duplicate P1 card must be de-duped away, first-seen product kept");
		assertEquals(new BigDecimal("3.79"), saved.get(0).getPriceAmount());
		assertEquals("newworld", saved.get(0).getChainId());
		assertEquals("pickup", saved.get(0).getChannel());
		assertTrue(saved.stream().noneMatch(PriceRecord::isPromoFlag));
	}

	@Test
	void dryRunScrapesAndDedupesButNeverPersists() {
		properties.setDryRun(true);
		enableChain(ChainId.NEWWORLD, "", "", "https://www.newworld.co.nz/shop/category/milk");

		StubChainScraper newworld = StubChainScraper.thatReturns(ChainId.NEWWORLD, ScrapeResult.builder()
				.chainId(ChainId.NEWWORLD)
				.products(List.of(product("newworld", "P1", "Anchor Milk 2L", "3.79")))
				.categoriesVisited(List.of("https://www.newworld.co.nz/shop/category/milk"))
				.zeroResultCategoryUrls(List.of())
				.warnings(List.of())
				.build());

		IngestScheduler scheduler = new IngestScheduler(List.of(newworld), browser, properties, priceRecordRepository);
		scheduler.runAll();

		assertEquals(1, newworld.invocations, "dry-run must still scrape, just not persist");
		verify(priceRecordRepository, never()).save(any(PriceRecord.class));
	}

	@Test
	void skipsChainThatIsNotEnabled() {
		// newworld left at its default (disabled), regardless of category-urls.
		StubChainScraper newworld = StubChainScraper.thatNeverRuns(ChainId.NEWWORLD);

		IngestScheduler scheduler = new IngestScheduler(List.of(newworld), browser, properties, priceRecordRepository);
		scheduler.runAll();

		assertEquals(0, newworld.invocations, "a disabled chain must never be scraped");
		verify(priceRecordRepository, never()).save(any(PriceRecord.class));
	}

	@Test
	void skipsChainThatHasNoCategoryUrlsConfigured() {
		ChainConfig config = new ChainConfig();
		config.setEnabled(true);
		config.setCategoryUrls(List.of());
		properties.setNewworld(config);

		StubChainScraper newworld = StubChainScraper.thatNeverRuns(ChainId.NEWWORLD);

		IngestScheduler scheduler = new IngestScheduler(List.of(newworld), browser, properties, priceRecordRepository);
		scheduler.runAll();

		assertEquals(0, newworld.invocations, "an enabled chain with no category-urls must be skipped, not scraped");
		verify(priceRecordRepository, never()).save(any(PriceRecord.class));
	}

	@Test
	void oneChainsScrapeExceptionDoesNotAbortTheOthers() {
		enableChain(ChainId.NEWWORLD, "", "", "https://www.newworld.co.nz/shop/category/milk");
		enableChain(ChainId.PAKNSAVE, "", "", "https://www.paknsave.co.nz/shop/category/milk");

		StubChainScraper newworld = StubChainScraper.thatThrows(ChainId.NEWWORLD,
				new ScrapeException("navigation timed out"));
		StubChainScraper paknsave = StubChainScraper.thatReturns(ChainId.PAKNSAVE, ScrapeResult.builder()
				.chainId(ChainId.PAKNSAVE)
				.products(List.of(product("paknsave", "P9", "Value Milk 2L", "3.49")))
				.categoriesVisited(List.of("https://www.paknsave.co.nz/shop/category/milk"))
				.zeroResultCategoryUrls(List.of())
				.warnings(List.of())
				.build());

		IngestScheduler scheduler = new IngestScheduler(List.of(newworld, paknsave), browser, properties, priceRecordRepository);
		scheduler.runAll();

		assertEquals(1, newworld.invocations);
		assertEquals(1, paknsave.invocations);

		ArgumentCaptor<PriceRecord> captor = ArgumentCaptor.forClass(PriceRecord.class);
		verify(priceRecordRepository, times(1)).save(captor.capture());
		assertEquals("paknsave:P9", captor.getValue().getItemId(),
				"paknsave's products must still be persisted despite newworld's scrape failing");
	}

	@Test
	void productWithBlankNativeProductCodeIsSkippedNotFatal() {
		enableChain(ChainId.WOOLWORTHS, "", "", "https://www.woolworths.co.nz/shop/category/milk");

		StubChainScraper woolworths = StubChainScraper.thatReturns(ChainId.WOOLWORTHS, ScrapeResult.builder()
				.chainId(ChainId.WOOLWORTHS)
				.products(List.of(
						product("woolworths", "", "Unidentifiable Milk 2L", "3.59"),
						product("woolworths", "W1", "Homebrand Milk 2L", "3.29")))
				.categoriesVisited(List.of("https://www.woolworths.co.nz/shop/category/milk"))
				.zeroResultCategoryUrls(List.of())
				.warnings(List.of())
				.build());

		IngestScheduler scheduler = new IngestScheduler(List.of(woolworths), browser, properties, priceRecordRepository);
		scheduler.runAll();

		ArgumentCaptor<PriceRecord> captor = ArgumentCaptor.forClass(PriceRecord.class);
		verify(priceRecordRepository, times(1)).save(captor.capture());
		assertEquals("woolworths:W1", captor.getValue().getItemId(),
				"the blank-nativeProductCode product must be skipped, not abort the whole chain's batch");
	}

	@Test
	void zeroResultCategoriesAndWarningsDoNotPreventPersistence() {
		enableChain(ChainId.NEWWORLD, "", "", "https://www.newworld.co.nz/shop/category/milk",
				"https://www.newworld.co.nz/shop/category/empty");

		StubChainScraper newworld = StubChainScraper.thatReturns(ChainId.NEWWORLD, ScrapeResult.builder()
				.chainId(ChainId.NEWWORLD)
				.products(List.of(product("newworld", "P1", "Anchor Milk 2L", "3.79")))
				.categoriesVisited(List.of(
						"https://www.newworld.co.nz/shop/category/milk",
						"https://www.newworld.co.nz/shop/category/empty"))
				.zeroResultCategoryUrls(List.of("https://www.newworld.co.nz/shop/category/empty"))
				.warnings(List.of("store-pin cookie had no effect"))
				.build());

		IngestScheduler scheduler = new IngestScheduler(List.of(newworld), browser, properties, priceRecordRepository);
		scheduler.runAll();

		verify(priceRecordRepository, times(1)).save(any(PriceRecord.class));
	}

	private void enableChain(ChainId chainId, String storeId, String storeName, String... categoryUrls) {
		ChainConfig config = new ChainConfig();
		config.setEnabled(true);
		config.setStoreId(storeId);
		config.setStoreName(storeName);
		config.setCategoryUrls(List.of(categoryUrls));
		switch (chainId) {
			case NEWWORLD -> properties.setNewworld(config);
			case PAKNSAVE -> properties.setPaknsave(config);
			case WOOLWORTHS -> properties.setWoolworths(config);
		}
	}

	private static RawProduct product(String chainId, String nativeProductCode, String name, String price) {
		return RawProduct.builder()
				.chainId(chainId)
				.nativeProductCode(nativeProductCode)
				.name(name)
				.price(new BigDecimal(price))
				.currency("NZD")
				.build();
	}

	/** Records invocations/config and either returns a fixed {@link ScrapeResult} or throws a fixed exception. */
	private static final class StubChainScraper implements ChainScraper {

		private final ChainId chainId;
		private final ScrapeResult result;
		private final RuntimeException failure;
		private int invocations = 0;
		private ChainScraperConfig lastConfig;

		private StubChainScraper(ChainId chainId, ScrapeResult result, RuntimeException failure) {
			this.chainId = chainId;
			this.result = result;
			this.failure = failure;
		}

		static StubChainScraper thatReturns(ChainId chainId, ScrapeResult result) {
			return new StubChainScraper(chainId, result, null);
		}

		static StubChainScraper thatThrows(ChainId chainId, RuntimeException failure) {
			return new StubChainScraper(chainId, null, failure);
		}

		/** Used to assert the scraper is never invoked (disabled chain / no category-urls). */
		static StubChainScraper thatNeverRuns(ChainId chainId) {
			return new StubChainScraper(chainId, null, null);
		}

		@Override
		public ChainId chainId() {
			return chainId;
		}

		@Override
		public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
			invocations++;
			lastConfig = config;
			if (failure != null) {
				throw failure;
			}
			return result;
		}
	}
}
