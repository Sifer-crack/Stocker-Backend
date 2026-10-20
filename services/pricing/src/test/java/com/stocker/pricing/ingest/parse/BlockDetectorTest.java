package com.stocker.pricing.ingest.parse;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockDetectorTest {

	@Test
	void statusesThatMeanBlockedOrRateLimited() {
		assertThat(BlockDetector.isBlockedStatus(429)).isTrue();
		assertThat(BlockDetector.isBlockedStatus(403)).isTrue();
		assertThat(BlockDetector.isBlockedStatus(503)).isTrue();
		assertThat(BlockDetector.isBlockedStatus(200)).isFalse();
		assertThat(BlockDetector.isBlockedStatus(404)).isFalse();
		assertThat(BlockDetector.isBlockedStatus(500)).isFalse();
	}

	@Test
	void challengePagesAreRecognisedFromTitleOrText() {
		assertThat(BlockDetector.looksLikeChallenge("Just a moment...", "")).isTrue();
		assertThat(BlockDetector.looksLikeChallenge("Attention Required! | Cloudflare", "")).isTrue();
		assertThat(BlockDetector.looksLikeChallenge("", "Please verify you are human to continue")).isTrue();
		assertThat(BlockDetector.looksLikeChallenge("", "Complete the CAPTCHA below")).isTrue();
		assertThat(BlockDetector.looksLikeChallenge("Access Denied", "")).isTrue();
	}

	@Test
	void anOrdinaryEmptyCategoryIsNotAChallenge() {
		assertThat(BlockDetector.looksLikeChallenge("Milk | New World", "No products found in this category")).isFalse();
		assertThat(BlockDetector.looksLikeChallenge(null, null)).isFalse();
	}

	@Test
	void retryAfterInSecondsAndAsAnHttpDate() {
		Instant now = Instant.parse("2026-10-20T00:00:00Z");

		assertThat(BlockDetector.parseRetryAfter("120", now)).contains(Duration.ofSeconds(120));
		assertThat(BlockDetector.parseRetryAfter(" 3600 ", now)).contains(Duration.ofHours(1));
		assertThat(BlockDetector.parseRetryAfter("Tue, 20 Oct 2026 01:00:00 GMT", now)).contains(Duration.ofHours(1));
		assertThat(BlockDetector.parseRetryAfter("Mon, 19 Oct 2026 01:00:00 GMT", now)).isEmpty();   // in the past
	}

	@Test
	void retryAfterThatIsMissingOrGarbageIsIgnored() {
		Instant now = Instant.now();

		assertThat(BlockDetector.parseRetryAfter(null, now)).isEmpty();
		assertThat(BlockDetector.parseRetryAfter("", now)).isEmpty();
		assertThat(BlockDetector.parseRetryAfter("soon", now)).isEmpty();
		assertThat(BlockDetector.parseRetryAfter("-5", now)).isEmpty();
	}
}
