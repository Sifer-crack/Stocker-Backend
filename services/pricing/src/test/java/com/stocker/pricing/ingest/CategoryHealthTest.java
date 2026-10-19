package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocker.pricing.ingest.model.ScrapeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryHealthTest {

	private static final String URL = "https://example.test/shop/category/milk?pg=1";
	private MutableClock clock;
	private CategoryHealth health;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
		health = new CategoryHealth(2, Duration.ofDays(7), clock);
	}

	private static ScrapeResult result(List<String> visited, List<String> zero, List<String> warnings) {
		return ScrapeResult.builder().chainId(ChainId.NEWWORLD).products(List.of()).categoriesVisited(visited)
				.zeroResultCategoryUrls(zero).warnings(warnings).build();
	}

	@Test
	void oneZeroRunIsNotEnoughButTheThresholdQuarantines() {
		health.recordZero(ChainId.NEWWORLD, URL);
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();

		health.recordZero(ChainId.NEWWORLD, URL);
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();
		assertThat(health.snapshot()).singleElement().satisfies(p -> {
			assertThat(p.chain()).isEqualTo(ChainId.NEWWORLD);
			assertThat(p.since()).isEqualTo(clock.instant());
			assertThat(p.until()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
		});
	}

	@Test
	void thresholdIsConfigurable() {
		CategoryHealth three = new CategoryHealth(3, Duration.ofDays(1), clock);
		three.recordZero(ChainId.NEWWORLD, URL);
		three.recordZero(ChainId.NEWWORLD, URL);
		assertThat(three.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();
		three.recordZero(ChainId.NEWWORLD, URL);
		assertThat(three.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();
	}

	@Test
	void aNonZeroResultClearsTheCounter() {
		health.recordZero(ChainId.NEWWORLD, URL);
		health.recordSuccess(ChainId.NEWWORLD, URL);
		health.recordZero(ChainId.NEWWORLD, URL);

		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();   // runs were not consecutive
	}

	@Test
	void expiryAllowsExactlyOneRetryAndAZeroRequarantinesAtOnce() {
		health.recordZero(ChainId.NEWWORLD, URL);
		health.recordZero(ChainId.NEWWORLD, URL);
		clock.advance(Duration.ofDays(6));
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();

		clock.advance(Duration.ofDays(1));
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();   // the retry
		assertThat(health.snapshot()).isEmpty();

		health.recordZero(ChainId.NEWWORLD, URL);                             // retry was empty again
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();
		assertThat(health.snapshot()).singleElement()
				.satisfies(p -> assertThat(p.until()).isEqualTo(clock.instant().plus(Duration.ofDays(7))));
	}

	@Test
	void aSuccessfulRetryClearsThePageForGood() {
		health.recordZero(ChainId.NEWWORLD, URL);
		health.recordZero(ChainId.NEWWORLD, URL);
		clock.advance(Duration.ofDays(7));
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();

		health.recordSuccess(ChainId.NEWWORLD, URL);
		health.recordZero(ChainId.NEWWORLD, URL);
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();   // back to needing two zeros
	}

	@Test
	void failedOrWarnedPagesAreNotCountedAsZero() {
		String warned = "https://example.test/warned";
		for (int i = 0; i < 5; i++) {
			// failed page: not visited at all, only a warning
			health.record(ChainId.NEWWORLD, result(List.of(), List.of(), List.of("Failed to scrape " + URL + ": timeout")));
			// visited and listed as zero, but a warning names it too
			health.record(ChainId.NEWWORLD, result(List.of(warned), List.of(warned), List.of("Failed to scrape " + warned)));
		}
		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isFalse();
		assertThat(health.isQuarantined(ChainId.NEWWORLD, warned)).isFalse();
		assertThat(health.snapshot()).isEmpty();
	}

	@Test
	void aFailureBetweenTwoZerosDoesNotResetTheCounter() {
		health.record(ChainId.NEWWORLD, result(List.of(URL), List.of(URL), List.of()));
		health.record(ChainId.NEWWORLD, result(List.of(), List.of(), List.of("Failed to scrape " + URL)));
		health.record(ChainId.NEWWORLD, result(List.of(URL), List.of(URL), List.of()));

		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();
	}

	@Test
	void recordClassifiesVisitedPagesFromTheScrapeResult() {
		String ok = "https://example.test/ok";
		health.record(ChainId.NEWWORLD, result(List.of(URL, ok), List.of(URL), List.of()));
		health.record(ChainId.NEWWORLD, result(List.of(URL, ok), List.of(URL), List.of()));

		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();
		assertThat(health.isQuarantined(ChainId.NEWWORLD, ok)).isFalse();
	}

	@Test
	void chainsAreTrackedSeparately() {
		health.recordZero(ChainId.NEWWORLD, URL);
		health.recordZero(ChainId.NEWWORLD, URL);
		health.recordZero(ChainId.PAKNSAVE, URL);

		assertThat(health.isQuarantined(ChainId.NEWWORLD, URL)).isTrue();
		assertThat(health.isQuarantined(ChainId.PAKNSAVE, URL)).isFalse();
		assertThat(health.isQuarantined(ChainId.WOOLWORTHS, URL)).isFalse();
	}

	@Test
	void firstPageSuffixDoesNotCreateASecondPage() {
		health.recordZero(ChainId.NEWWORLD, "https://example.test/x?pg=1");
		health.recordZero(ChainId.NEWWORLD, "https://example.test/x");

		assertThat(health.isQuarantined(ChainId.NEWWORLD, "https://example.test/x?pg=1")).isTrue();
	}
}
