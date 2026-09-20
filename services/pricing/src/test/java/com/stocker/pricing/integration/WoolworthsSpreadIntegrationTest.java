package com.stocker.pricing.integration;

import com.stocker.pricing.fetch.WebFetcher;
import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.fetch.model.WebFetchRequest;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.RawProductPriceRecordMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live integration test for the price-engine web scrapers.
 * <p>
 * Scrapes Woolworths (via the active {@code WebFetcher} provider, default
 * {@code spread}) for three known products, then maps the crawled results to
 * {@code PriceRecord}s and persists them to the configured database
 * ({@code price_records}, i.e. {@code STOCKER_DB_URL}).
 * <p>
 * Disabled entirely unless {@code STOCKER_SPREAD_API_KEY} is set (see repo-root
 * {@code .env}); the outer class guard prevents the Spring context from booting
 * when the key is absent.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOCKER_SPREAD_API_KEY", matches = "\\S+")
class WoolworthsSpreadIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(WoolworthsSpreadIntegrationTest.class);

	private static final List<String> PRODUCTS = List.of(
			"Tim Tam",
			"Coca-Cola 2.25L",
			"Copper Kettle");

	@Nested
	@SpringBootTest(properties = {
			"spring.kafka.listener.auto-startup=false",
			"spring.kafka.bootstrap-servers=localhost:1"
	})
	class ScraperPersistsPrices {

		@Autowired
		private WebFetcher webFetcher;

		@Autowired
		private PriceRecordRepository priceRecordRepository;

		@Test
		void scrapesWoolworthsPricesAndPersistsThem() {
			for (String query : PRODUCTS) {
				List<RawProduct> products = webFetcher.fetch(WebFetchRequest.builder().searchTerm(query).build());
				assertFalse(products.isEmpty(), "spread returned no products for '" + query + "'");

				List<RawProduct> woolworths = products.stream()
						.filter(WoolworthsSpreadIntegrationTest::isWoolworths)
						.collect(toList());
				assertFalse(woolworths.isEmpty(),
						"no Woolworths results for '" + query + "' (got " + products.size() + " total)");

				String itemId = "woolworths-" + slugify(query);
				List<PriceRecord> saved = woolworths.stream()
						.map(product -> RawProductPriceRecordMapper.toPriceRecord(itemId, product))
						.map(priceRecordRepository::save)
						.collect(toList());

				assertFalse(saved.isEmpty(), "no price records persisted for '" + query + "'");
				assertTrue(saved.stream().allMatch(record -> record.getPriceAmount() != null
						&& record.getPriceAmount().compareTo(BigDecimal.ZERO) > 0),
						"every persisted record must carry a positive price for '" + query + "'");
				log.info("Persisted {} Woolworths records for '{}' -> itemId={}",
						saved.size(), query, itemId);
			}
		}
	}

	private static boolean isWoolworths(RawProduct product) {
		String haystack = join(product.getSource(), product.getChainId(), product.getStoreUrl())
				.toLowerCase(Locale.ROOT);
		return haystack.contains("woolworths") || haystack.contains("countdown");
	}

	private static String join(String... values) {
		StringBuilder sb = new StringBuilder();
		for (String value : values) {
			if (value != null) {
				sb.append(value).append(' ');
			}
		}
		return sb.toString();
	}

	private static String slugify(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
	}
}