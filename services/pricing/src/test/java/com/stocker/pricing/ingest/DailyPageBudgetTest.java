package com.stocker.pricing.ingest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hard cap on supermarket page loads per chain per day (Auckland day), shared by the scheduled crawl and the
 * on-demand refresh, so no combination of lookups and runs can exceed what was approved.
 */
class DailyPageBudgetTest {

	private static final class MutableClock extends Clock {
		Instant now = Instant.parse("2026-10-19T14:00:00Z");   // 03:00 on 20 Oct in Auckland (NZDT, UTC+13)

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private MutableClock clock;
	private DailyPageBudget budget;

	@BeforeEach
	void setUp() {
		clock = new MutableClock();
		budget = new DailyPageBudget(10, clock);
	}

	@Test
	void grantsWhatIsRequestedWhileUnderTheCap() {
		assertThat(budget.reserve(ChainId.NEWWORLD, 4)).isEqualTo(4);
		assertThat(budget.remaining(ChainId.NEWWORLD)).isEqualTo(6);
	}

	@Test
	void grantsOnlyWhatIsLeftAndThenNothing() {
		assertThat(budget.reserve(ChainId.PAKNSAVE, 8)).isEqualTo(8);
		assertThat(budget.reserve(ChainId.PAKNSAVE, 8)).isEqualTo(2);    // partial: only 2 left
		assertThat(budget.reserve(ChainId.PAKNSAVE, 1)).isZero();
		assertThat(budget.remaining(ChainId.PAKNSAVE)).isZero();
	}

	@Test
	void eachChainHasItsOwnBudget() {
		budget.reserve(ChainId.NEWWORLD, 10);

		assertThat(budget.reserve(ChainId.WOOLWORTHS, 10)).isEqualTo(10);
	}

	@Test
	void theBudgetResetsAtMidnightInAuckland() {
		budget.reserve(ChainId.NEWWORLD, 10);
		clock.now = Instant.parse("2026-10-20T10:59:00Z");   // 23:59 Auckland, same day
		assertThat(budget.reserve(ChainId.NEWWORLD, 1)).isZero();

		clock.now = Instant.parse("2026-10-20T11:01:00Z");   // 00:01 Auckland, next day
		assertThat(budget.reserve(ChainId.NEWWORLD, 5)).isEqualTo(5);
	}

	@Test
	void nonsenseRequestsGrantNothing() {
		assertThat(budget.reserve(ChainId.NEWWORLD, 0)).isZero();
		assertThat(budget.reserve(ChainId.NEWWORLD, -3)).isZero();
		assertThat(budget.remaining(ChainId.NEWWORLD)).isEqualTo(10);
	}

	@Test
	void aZeroCapMeansNoPageIsEverLoaded() {
		assertThat(new DailyPageBudget(0, clock).reserve(ChainId.NEWWORLD, 5)).isZero();
	}

	@Test
	void giveBackReturnsPagesThatWereNotActuallyLoaded() {
		budget.reserve(ChainId.NEWWORLD, 10);

		budget.giveBack(ChainId.NEWWORLD, 4);   // e.g. the browser failed before loading them

		assertThat(budget.remaining(ChainId.NEWWORLD)).isEqualTo(4);
	}
}
