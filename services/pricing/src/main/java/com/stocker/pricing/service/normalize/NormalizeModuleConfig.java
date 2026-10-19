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
			thread.start();
		};
	}
}
