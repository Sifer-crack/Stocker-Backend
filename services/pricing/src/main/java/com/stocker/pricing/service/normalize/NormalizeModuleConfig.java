package com.stocker.pricing.service.normalize;

import com.stocker.pricing.normalize.CommonProductCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NormalizeModuleConfig {

	private static final Logger log = LoggerFactory.getLogger(NormalizeModuleConfig.class);

	/** The backfill's thread, so the re-key can wait for it (ApplicationRunners run in order on the main thread). */
	private volatile Thread backfillThread;

	@Bean
	com.stocker.pricing.refresh.CategoryMap categoryMap() {
		return com.stocker.pricing.refresh.CategoryMap.loadDefault();
	}

	@Bean
	com.stocker.pricing.refresh.CategoryRouter categoryRouter() {
		return com.stocker.pricing.refresh.CategoryRouter.loadDefault();
	}

	@Bean
	CommonProductCatalog commonProductCatalog() {
		CommonProductCatalog catalog = CommonProductCatalog.loadDefault();
		log.info("Loaded {} common NZ products", catalog.all().size());
		return catalog;
	}

	/**
	 * One-off catch-up for rows scraped before normalized_products existed; database work only. Runs on its own
	 * daemon thread: a large backlog is thousands of Neon round trips and must not delay startup or block shutdown.
	 */
	@Bean
	@org.springframework.core.annotation.Order(1)
	ApplicationRunner normalizedProductBackfill(NormalizedProductService service,
			@Value("${app.normalize.backfill-on-startup:true}") boolean enabled) {
		return args -> {
			if (!enabled) {
				return;
			}
			Thread thread = new Thread(() -> {
				try {
					int done = service.backfillAll(500, 200);
					if (done > 0) {
						log.info("Backfilled {} normalized products from price_records", done);
					}
				}
				catch (RuntimeException e) {
					log.warn("normalized_products backfill skipped: {}", e.getMessage());
				}
			}, "normalize-backfill");
			thread.setDaemon(true);
			backfillThread = thread;
			thread.start();
		};
	}

	/**
	 * Re-derives canonical_key, pack size and unit price of stored rows with the current catalog (database work only;
	 * only changed rows are written). Runs after the backfill on its own daemon thread so it never blocks startup or
	 * shutdown; failures are logged, not thrown.
	 */
	@Bean
	@org.springframework.core.annotation.Order(2)
	ApplicationRunner normalizedProductRekey(NormalizedProductService service,
			@Value("${app.normalize.rekey-on-startup:true}") boolean enabled,
			@Value("${app.normalize.backfill-on-startup:true}") boolean backfillEnabled) {
		return args -> {
			if (!enabled) {
				return;
			}
			Thread thread = new Thread(() -> {
				try {
					if (backfillEnabled) {
						// let the backfill's thread finish first so freshly added rows are re-keyed too
						Thread backfill = backfillThread;
						if (backfill != null) {
							backfill.join(600_000);
						}
					}
					service.rekeyAll(200);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				catch (RuntimeException e) {
					log.warn("normalized_products re-key skipped: {}", e.getMessage());
				}
			}, "normalize-rekey");
			thread.setDaemon(true);
			thread.start();
		};
	}
}
