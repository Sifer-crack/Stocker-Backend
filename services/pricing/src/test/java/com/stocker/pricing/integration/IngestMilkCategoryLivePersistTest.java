package com.stocker.pricing.integration;

import com.stocker.pricing.PricingApplication;
import com.stocker.pricing.ingest.IngestScheduler;
import com.stocker.pricing.repository.PriceRecordRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-off, manually-triggered real persistence run for the ingest Day-1 checklist's final step:
 * boots the full Spring context (real Postgres/Flyway/JPA, real IngestScheduler bean wired via
 * IngestModuleConfig since app.ingest.enabled=true) and calls IngestScheduler.runAll() directly,
 * bypassing its @Scheduled cron trigger so this runs deterministically instead of racing a timed
 * cron window. Writes real rows into whatever database STOCKER_DB_URL points at (the .env values
 * are already injected into the test JVM by build.gradle's readDotEnv) - gated behind an explicit
 * env var, never run as part of the normal suite. Not a candidate for CI: it deliberately mutates
 * a real external database.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_INGEST_MILK_LIVE_PERSIST", matches = "true")
@SpringBootTest(classes = PricingApplication.class, properties = "app.ingest.dry-run=false")
class IngestMilkCategoryLivePersistTest {

	private static final Logger log = LoggerFactory.getLogger(IngestMilkCategoryLivePersistTest.class);

	@Autowired
	private IngestScheduler ingestScheduler;

	@Autowired
	private PriceRecordRepository priceRecordRepository;

	@Test
	void runsOnceAndPersistsRealPriceRecords() {
		long before = priceRecordRepository.count();
		ingestScheduler.runAll();
		long after = priceRecordRepository.count();

		log.info("price_records count before={}, after={}", before, after);
		assertTrue(after > before, "expected new rows to be persisted by this real ingest run");
	}
}
