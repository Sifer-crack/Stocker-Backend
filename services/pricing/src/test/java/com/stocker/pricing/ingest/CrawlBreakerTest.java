package com.stocker.pricing.ingest;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-chain circuit breaker: trips after N consecutive blocked responses, cools down, doubles, resets. */
class CrawlBreakerTest {

	private static final Instant START = Instant.parse("2026-10-20T00:00:00Z");

	private MutableClock clock;
	private CrawlBreaker breaker;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(START);
		breaker = new CrawlBreaker(() -> 3, () -> Duration.ofHours(6), () -> Duration.ofHours(24), clock);
	}

	private void block(ChainId chain, int times) {
		for (int i = 0; i < times; i++) {
			breaker.recordBlocked(chain, "HTTP 429", null);
		}
	}

	@Test
	void startsClosed() {
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		assertThat(breaker.openUntil(ChainId.NEWWORLD)).isNull();
	}

	@Test
	void tripsOnTheThirdConsecutiveBlockedResponseNotBefore() {
		assertThat(breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null)).isFalse();
		assertThat(breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null)).isFalse();
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();

		assertThat(breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 403", null)).isTrue();

		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();
		assertThat(breaker.openUntil(ChainId.NEWWORLD)).isEqualTo(START.plus(Duration.ofHours(6)));
	}

	@Test
	void aSuccessfulPageResetsTheConsecutiveCounter() {
		block(ChainId.NEWWORLD, 2);
		breaker.recordSuccess(ChainId.NEWWORLD);
		block(ChainId.NEWWORLD, 2);

		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		block(ChainId.NEWWORLD, 1);
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();
	}

	@Test
	void staysOpenForTheCoolDownThenClosesAndLoadsAreAllowedAgain() {
		block(ChainId.NEWWORLD, 3);

		clock.advance(Duration.ofHours(6).minusSeconds(1));
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();

		clock.advance(Duration.ofSeconds(1));
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		assertThat(breaker.openUntil(ChainId.NEWWORLD)).isNull();
	}

	@Test
	void whileOpenFurtherBlockedReportsDoNotExtendOrReTrip() {
		block(ChainId.NEWWORLD, 3);
		Instant until = breaker.openUntil(ChainId.NEWWORLD);

		assertThat(breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null)).isFalse();

		assertThat(breaker.openUntil(ChainId.NEWWORLD)).isEqualTo(until);
	}

	@Test
	void theCoolDownDoublesOnEachConsecutiveTripAndIsCappedAt24Hours() {
		block(ChainId.NEWWORLD, 3);                                  // 6 h
		assertThat(remaining()).isEqualTo(Duration.ofHours(6));

		clock.advance(Duration.ofHours(6));
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		block(ChainId.NEWWORLD, 1);                                  // first page after the cool-down is blocked again
		assertThat(remaining()).isEqualTo(Duration.ofHours(12));

		clock.advance(Duration.ofHours(12));
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		block(ChainId.NEWWORLD, 1);
		assertThat(remaining()).isEqualTo(Duration.ofHours(24));

		clock.advance(Duration.ofHours(24));
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
		block(ChainId.NEWWORLD, 1);
		assertThat(remaining()).isEqualTo(Duration.ofHours(24));     // capped
	}

	@Test
	void threeGoodPagesInARowAfterACoolDownStartTheCoolDownSequenceOverAtSixHours() {
		block(ChainId.NEWWORLD, 3);
		clock.advance(Duration.ofHours(6));
		breaker.isOpen(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);

		block(ChainId.NEWWORLD, 3);

		assertThat(remaining()).isEqualTo(Duration.ofHours(6));
	}

	@Test
	void oneLuckyPageBetweenBlocksDoesNotResetTheDoublingCoolDown() {
		block(ChainId.NEWWORLD, 3);
		clock.advance(Duration.ofHours(6));
		breaker.isOpen(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);          // one good page only

		block(ChainId.NEWWORLD, 3);

		assertThat(remaining()).isEqualTo(Duration.ofHours(12));
	}

	@Test
	void aBlockedResponseRestartsTheCountOfGoodPagesInARow() {
		block(ChainId.NEWWORLD, 3);
		clock.advance(Duration.ofHours(6));
		breaker.isOpen(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null);       // a block in between: the good count starts over
		breaker.recordSuccess(ChainId.NEWWORLD);                           // 3 in total, but only 1 since the block

		block(ChainId.NEWWORLD, 3);

		assertThat(remaining()).isEqualTo(Duration.ofHours(12));          // the history was not cleared: second trip
	}

	@Test
	void theNumberOfGoodPagesThatResetIsConfigurable() {
		breaker = new CrawlBreaker(() -> 3, () -> Duration.ofHours(6), () -> Duration.ofHours(24), clock, () -> 1);
		block(ChainId.NEWWORLD, 3);
		clock.advance(Duration.ofHours(6));
		breaker.isOpen(ChainId.NEWWORLD);
		breaker.recordSuccess(ChainId.NEWWORLD);

		block(ChainId.NEWWORLD, 3);

		assertThat(remaining()).isEqualTo(Duration.ofHours(6));
	}

	@Test
	void openUntilIsReadOnlyAndDoesNotCloseTheBreakerAfterTheCoolDown() {
		block(ChainId.NEWWORLD, 3);
		clock.advance(Duration.ofHours(7));

		assertThat(breaker.openUntil(ChainId.NEWWORLD)).isNull();       // cool-down over: nothing to report
		assertThat(breaker.openUntil(ChainId.NEWWORLD)).isNull();       // asking twice changes nothing
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();         // the real check still closes it
	}

	@Test
	void chainsAreIndependent() {
		block(ChainId.NEWWORLD, 3);

		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isTrue();
		assertThat(breaker.isOpen(ChainId.PAKNSAVE)).isFalse();
		assertThat(breaker.isOpen(ChainId.WOOLWORTHS)).isFalse();
		block(ChainId.PAKNSAVE, 2);
		breaker.recordSuccess(ChainId.WOOLWORTHS);
		assertThat(breaker.isOpen(ChainId.PAKNSAVE)).isFalse();
	}

	@Test
	void aLongerRetryAfterWinsOverTheDefaultCoolDown() {
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", Duration.ofHours(2));
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", null);
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", Duration.ofHours(10));

		assertThat(remaining()).isEqualTo(Duration.ofHours(10));
	}

	@Test
	void aShorterRetryAfterNeverShortensTheCoolDownAndAbsurdOnesAreCapped() {
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", Duration.ofSeconds(30));
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", Duration.ofSeconds(30));
		breaker.recordBlocked(ChainId.NEWWORLD, "HTTP 429", Duration.ofSeconds(30));
		assertThat(remaining()).isEqualTo(Duration.ofHours(6));

		breaker.recordSuccess(ChainId.PAKNSAVE);
		breaker.recordBlocked(ChainId.PAKNSAVE, "HTTP 429", Duration.ofDays(30));
		breaker.recordBlocked(ChainId.PAKNSAVE, "HTTP 429", null);
		breaker.recordBlocked(ChainId.PAKNSAVE, "HTTP 429", null);
		assertThat(Duration.between(START, breaker.openUntil(ChainId.PAKNSAVE))).isEqualTo(Duration.ofHours(24));
	}

	@Test
	void theThresholdIsConfigurable() {
		breaker = new CrawlBreaker(() -> 1, () -> Duration.ofHours(6), () -> Duration.ofHours(24), clock);

		assertThat(breaker.recordBlocked(ChainId.WOOLWORTHS, "captcha", null)).isTrue();
	}

	@Test
	void theBrakeViewOfOneChainReportsToTheSameState() {
		var brake = breaker.forChain(ChainId.PAKNSAVE);
		brake.onBlocked("HTTP 429", null);
		brake.onBlocked("HTTP 429", null);
		assertThat(brake.isOpen()).isFalse();
		brake.onBlocked("HTTP 429", null);

		assertThat(brake.isOpen()).isTrue();
		assertThat(breaker.isOpen(ChainId.PAKNSAVE)).isTrue();
		assertThat(breaker.isOpen(ChainId.NEWWORLD)).isFalse();
	}

	private Duration remaining() {
		return Duration.between(clock.instant(), breaker.openUntil(ChainId.NEWWORLD));
	}
}
