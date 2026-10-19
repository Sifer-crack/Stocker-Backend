package com.stocker.pricing.ingest;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The daily page budget is the one gate every crawl goes through: an open breaker grants no page at all. */
class DailyPageBudgetBreakerTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T00:00:00Z"));
	private final CrawlBreaker breaker = new CrawlBreaker(() -> 3, () -> Duration.ofHours(6), () -> Duration.ofHours(24), clock);
	private final DailyPageBudget budget = new DailyPageBudget(() -> 140, clock, breaker);

	@Test
	void anOpenBreakerGrantsNoPagesToAnyCaller() {
		assertThat(budget.reserve(ChainId.NEWWORLD, 10)).isEqualTo(10);
		for (int i = 0; i < 3; i++) {
			breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null);
		}

		assertThat(budget.isBlocked(ChainId.NEWWORLD)).isTrue();
		assertThat(budget.reserve(ChainId.NEWWORLD, 1)).isZero();      // extra page of a category
		assertThat(budget.reserve(ChainId.NEWWORLD, 20)).isZero();     // scheduled run or on-demand refresh
	}

	@Test
	void otherChainsKeepTheirBudget() {
		for (int i = 0; i < 3; i++) {
			breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null);
		}

		assertThat(budget.isBlocked(ChainId.PAKNSAVE)).isFalse();
		assertThat(budget.reserve(ChainId.PAKNSAVE, 5)).isEqualTo(5);
	}

	@Test
	void pagesAreGrantedAgainOnceTheCoolDownIsOver() {
		for (int i = 0; i < 3; i++) {
			breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null);
		}
		clock.advance(Duration.ofHours(6));

		assertThat(budget.isBlocked(ChainId.NEWWORLD)).isFalse();
		assertThat(budget.reserve(ChainId.NEWWORLD, 5)).isEqualTo(5);
	}

	@Test
	void withoutABreakerNothingIsBlocked() {
		DailyPageBudget plain = new DailyPageBudget(10, clock);

		assertThat(plain.isBlocked(ChainId.NEWWORLD)).isFalse();
		assertThat(plain.reserve(ChainId.NEWWORLD, 3)).isEqualTo(3);
	}
}
