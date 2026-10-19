package com.stocker.pricing.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.ingest.model.ChainScraperConfig;
import com.stocker.pricing.ingest.model.ScrapeResult;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.PriceStatsService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IngestSchedulerConcurrencyTest {

	private static ChainScraper scraper(ChainId chain, AtomicInteger running, AtomicInteger maxRunning) {
		return new ChainScraper() {
			@Override
			public ChainId chainId() {
				return chain;
			}

			@Override
			public ScrapeResult scrape(Browser browser, ChainScraperConfig config) {
				int now = running.incrementAndGet();
				maxRunning.accumulateAndGet(now, Math::max);
				try {
					Thread.sleep(150);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				running.decrementAndGet();
				return ScrapeResult.builder().chainId(chain).products(List.of()).categoriesVisited(List.of("u"))
						.zeroResultCategoryUrls(List.of()).warnings(List.of()).build();
			}
		};
	}

	@Test
	void scrapesFromDifferentThreadsNeverUseTheBrowserAtTheSameTime() throws Exception {
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		IngestProperties properties = new IngestProperties();
		properties.setDryRun(true);
		properties.getNewworld().setEnabled(true);
		properties.getPaknsave().setEnabled(true);
		properties.getWoolworths().setEnabled(true);
		IngestScheduler scheduler = new IngestScheduler(
				List.of(scraper(ChainId.NEWWORLD, running, maxRunning), scraper(ChainId.PAKNSAVE, running, maxRunning),
						scraper(ChainId.WOOLWORTHS, running, maxRunning)),
				mock(Browser.class), properties, mock(PriceRecordRepository.class), mock(PriceStatsService.class));

		CountDownLatch go = new CountDownLatch(1);
		List<Thread> threads = List.of(ChainId.NEWWORLD, ChainId.PAKNSAVE, ChainId.WOOLWORTHS).stream()
				.map(chain -> new Thread(() -> {
					try {
						go.await();
					} catch (InterruptedException e) {
						return;
					}
					scheduler.refreshCategories(chain, List.of("https://example.test/" + chain));
				})).toList();
		threads.forEach(Thread::start);
		go.countDown();
		for (Thread thread : threads) {
			thread.join();
		}

		assertEquals(1, maxRunning.get(), "browser work must be serialised");
	}
}
