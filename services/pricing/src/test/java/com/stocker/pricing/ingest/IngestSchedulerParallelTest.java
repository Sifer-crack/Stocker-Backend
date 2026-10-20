package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeException;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.refresh.CategoryMap;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IngestSchedulerParallelTest {

	private static final List<ChainId> CHAINS = List.of(ChainId.NEWWORLD, ChainId.PAKNSAVE, ChainId.WOOLWORTHS);

	private final List<ManagedBrowser> started = new CopyOnWriteArrayList<>();
	private final List<ManagedBrowser> closedBrowsers = new CopyOnWriteArrayList<>();

	private static Browser connectedBrowser() {
		Browser browser = mock(Browser.class);
		org.mockito.Mockito.when(browser.isConnected()).thenReturn(true);
		return browser;
	}

	private ManagedBrowser newManaged() {
		Browser browser = connectedBrowser();
		ManagedBrowser managed = new ManagedBrowser() {
			@Override
			public Browser browser() {
				return browser;
			}

			@Override
			public void close() {
				closedBrowsers.add(this);
			}
		};
		started.add(managed);
		return managed;
	}

	private IngestProperties properties(boolean parallel) {
		IngestProperties properties = new IngestProperties();
		properties.setDryRun(true);
		properties.setParallelChains(parallel);
		properties.setMaxCategoriesPerRun(0);
		for (var chain : List.of(properties.getNewworld(), properties.getPaknsave(), properties.getWoolworths())) {
			chain.setEnabled(true);
			chain.setCategoryUrls(List.of("https://example.test/cat"));
		}
		return properties;
	}

	private IngestScheduler scheduler(IngestProperties properties, ChainBrowsers browsers, List<ChainScraper> scrapers) {
		return new IngestScheduler(scrapers, browsers, properties, mock(PriceRecordRepository.class),
				mock(PriceStatsService.class), CategoryMap.empty(), Clock.systemUTC());
	}

	private static ScrapeResult empty(ChainId chain) {
		return ScrapeResult.builder().chainId(chain).products(List.of()).categoriesVisited(List.of("u"))
				.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
	}

	/** Scraper that waits (up to 5 s) until all three chains are inside scrape() at once. */
	private ChainScraper rendezvous(ChainId chain, CountDownLatch all, AtomicInteger overlapOk,
			Map<ChainId, Browser> seen) {
		return new ChainScraper() {
			public ChainId chainId() {
				return chain;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				seen.put(chain, browser);
				all.countDown();
				try {
					if (all.await(5, TimeUnit.SECONDS)) {
						overlapOk.incrementAndGet();
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return empty(chain);
			}
		};
	}

	@Test
	void parallelModeRunsAllChainsAtTheSameTimeEachOnItsOwnBrowser() {
		CountDownLatch all = new CountDownLatch(3);
		AtomicInteger overlap = new AtomicInteger();
		Map<ChainId, Browser> seen = new ConcurrentHashMap<>();
		List<ChainScraper> scrapers = new ArrayList<>();
		CHAINS.forEach(c -> scrapers.add(rendezvous(c, all, overlap, seen)));
		PerChainBrowsers browsers = new PerChainBrowsers(this::newManaged);

		scheduler(properties(true), browsers, scrapers).runAll();

		assertThat(overlap.get()).as("all three chains were inside scrape() together").isEqualTo(3);
		assertThat(seen.values()).hasSize(3).doesNotHaveDuplicates();
		assertThat(started).hasSize(3);
		browsers.close();
		assertThat(closedBrowsers).containsExactlyInAnyOrderElementsOf(started);
	}

	@Test
	void sequentialModeRunsOneChainAtATimeOnTheSharedBrowser() {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger max = new AtomicInteger();
		List<Browser> seen = new CopyOnWriteArrayList<>();
		Browser shared = mock(Browser.class);
		List<ChainScraper> scrapers = CHAINS.stream().map(chain -> (ChainScraper) new ChainScraper() {
			public ChainId chainId() {
				return chain;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				max.accumulateAndGet(running.incrementAndGet(), Math::max);
				seen.add(browser);
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				running.decrementAndGet();
				return empty(chain);
			}
		}).toList();

		scheduler(properties(false), ChainBrowsers.shared(shared), scrapers).runAll();

		assertThat(max.get()).isEqualTo(1);
		assertThat(seen).containsExactly(shared, shared, shared);
	}

	@Test
	void aFailingChainDoesNotStopTheOthers() {
		AtomicInteger done = new AtomicInteger();
		List<ChainScraper> scrapers = new ArrayList<>();
		for (ChainId chain : CHAINS) {
			scrapers.add(new ChainScraper() {
				public ChainId chainId() {
					return chain;
				}

				public ScrapeResult scrape(Browser browser, ChainScraperConfig config) throws ScrapeException {
					if (chain == ChainId.PAKNSAVE) {
						throw new IllegalStateException("boom");   // even an unchecked failure
					}
					done.incrementAndGet();
					return empty(chain);
				}
			});
		}

		new PerChainBrowsersHolder().run(this, properties(true), scrapers);

		assertThat(done.get()).isEqualTo(2);
	}

	@Test
	void aBrowserThatFailsToStartOnlyAffectsItsChain() {
		AtomicInteger done = new AtomicInteger();
		AtomicInteger launches = new AtomicInteger();
		PerChainBrowsers browsers = new PerChainBrowsers(() -> {
			if (launches.incrementAndGet() == 1) {
				throw new IllegalStateException("no firefox");
			}
			return newManaged();
		});
		List<ChainScraper> scrapers = CHAINS.stream().map(chain -> (ChainScraper) new ChainScraper() {
			public ChainId chainId() {
				return chain;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				done.incrementAndGet();
				return empty(chain);
			}
		}).toList();

		scheduler(properties(true), browsers, scrapers).runAll();

		assertThat(done.get()).isEqualTo(2);
		browsers.close();
	}

	@Test
	void onDemandRefreshUsesTheChainsOwnBrowserAndNeverTwoScrapesOnOne() throws Exception {
		AtomicInteger runningNw = new AtomicInteger();
		AtomicInteger maxNw = new AtomicInteger();
		CountDownLatch nwInside = new CountDownLatch(1);
		Map<ChainId, Browser> seen = new ConcurrentHashMap<>();
		List<ChainScraper> scrapers = CHAINS.stream().map(chain -> (ChainScraper) new ChainScraper() {
			public ChainId chainId() {
				return chain;
			}

			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				seen.put(chain, browser);
				if (chain == ChainId.NEWWORLD) {
					maxNw.accumulateAndGet(runningNw.incrementAndGet(), Math::max);
					nwInside.countDown();
					try {
						Thread.sleep(200);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					runningNw.decrementAndGet();
				}
				return empty(chain);
			}
		}).toList();
		PerChainBrowsers browsers = new PerChainBrowsers(this::newManaged);
		IngestScheduler scheduler = scheduler(properties(true), browsers, scrapers);

		Thread first = new Thread(() -> scheduler.refreshCategories(ChainId.NEWWORLD, List.of("https://example.test/a")));
		first.start();
		assertThat(nwInside.await(5, TimeUnit.SECONDS)).isTrue();
		// another chain is not held up by NEWWORLD's scrape, a second NEWWORLD refresh waits for the first
		scheduler.refreshCategories(ChainId.PAKNSAVE, List.of("https://example.test/b"));
		assertThat(runningNw.get()).as("pak'nsave refreshed while new world was still scraping").isEqualTo(1);
		scheduler.refreshCategories(ChainId.NEWWORLD, List.of("https://example.test/c"));
		first.join();

		assertThat(maxNw.get()).isEqualTo(1);
		assertThat(seen.get(ChainId.NEWWORLD)).isNotSameAs(seen.get(ChainId.PAKNSAVE));
		browsers.close();
	}

	@Test
	void closingClosesEveryStartedBrowserAndRefusesNewOnes() {
		PerChainBrowsers browsers = new PerChainBrowsers(this::newManaged);
		browsers.forChain(ChainId.NEWWORLD);
		browsers.forChain(ChainId.WOOLWORTHS);
		browsers.forChain(ChainId.NEWWORLD);   // reused, not started again

		assertThat(started).hasSize(2);
		browsers.close();
		assertThat(closedBrowsers).hasSize(2);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> browsers.forChain(ChainId.PAKNSAVE));
	}

	@Test
	void closeStillClosesTheRestWhenOneCloseFails() {
		List<String> closed = new CopyOnWriteArrayList<>();
		PerChainBrowsers browsers = new PerChainBrowsers(() -> {
			int n = closed.size() + started.size();
			ManagedBrowser m = new ManagedBrowser() {
				public Browser browser() {
					return connectedBrowser();
				}

				public void close() {
					closed.add("x");
					if (n == 0) {
						throw new IllegalStateException("driver gone");
					}
				}
			};
			started.add(m);
			return m;
		});
		browsers.forChain(ChainId.NEWWORLD);
		browsers.forChain(ChainId.PAKNSAVE);
		browsers.close();
		assertThat(closed).hasSize(2);
	}

	/** Small helper so the failing-chain test builds its own pool. */
	private static final class PerChainBrowsersHolder {
		void run(IngestSchedulerParallelTest t, IngestProperties props, List<ChainScraper> scrapers) {
			PerChainBrowsers browsers = new PerChainBrowsers(t::newManaged);
			t.scheduler(props, browsers, scrapers).runAll();
			browsers.close();
		}
	}
}
