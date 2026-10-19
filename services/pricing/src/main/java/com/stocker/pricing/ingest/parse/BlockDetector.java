package com.stocker.pricing.ingest.parse;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Recognises "the site is refusing us" responses: blocked HTTP statuses, captcha pages and a Retry-After header. */
public final class BlockDetector {

	private static final List<String> CHALLENGE_MARKERS = List.of("just a moment", "attention required",
			"access denied", "verify you are human", "verify you are a human", "captcha", "unusual traffic",
			"are you a robot", "security check");

	private BlockDetector() {
	}

	public static boolean isBlockedStatus(int status) {
		return status == 429 || status == 403 || status == 503;
	}

	/** Title or visible text of a page that has no products on it; true when it reads like a bot-check page. */
	public static boolean looksLikeChallenge(String title, String text) {
		String haystack = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).toLowerCase(Locale.ROOT);
		return CHALLENGE_MARKERS.stream().anyMatch(haystack::contains);
	}

	/** Retry-After as delta-seconds or an HTTP date; empty when absent, unparseable, or not in the future. */
	public static Optional<Duration> parseRetryAfter(String header, Instant now) {
		if (header == null || header.isBlank()) {
			return Optional.empty();
		}
		String value = header.trim();
		if (value.matches("\\d+")) {
			try {
				return Optional.of(Duration.ofSeconds(Long.parseLong(value))).filter(d -> !d.isZero());
			} catch (NumberFormatException e) {
				return Optional.empty();
			}
		}
		try {
			Duration wait = Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
			return wait.isNegative() || wait.isZero() ? Optional.empty() : Optional.of(wait);
		} catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}
}
