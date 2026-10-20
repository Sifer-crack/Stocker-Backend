package com.stocker.pricing.ingest;

import com.stocker.pricing.ingest.model.CrawlBrake;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-chain circuit breaker for the supermarket crawl. After {@code threshold} consecutive blocked responses (HTTP
 * 429 / 403 / 503 or a captcha page) the chain opens: no page of it is loaded by any crawl until the cool-down is
 * over. The cool-down starts at {@code cooldown} and doubles on every consecutive trip up to {@code maxCooldown}; a
 * larger Retry-After from the response wins (still bounded by {@code maxCooldown}). After the cool-down the chain is
 * half-open: pages load again, one blocked response trips it straight back, and {@code successesToReset} good pages
 * IN A ROW (default 3) clear the trip history, so one lucky page between blocks does not restart the cool-downs at the
 * shortest value.
 * In memory: a restart resets it. Logs one WARN when it opens and one when it closes.
 */
public class CrawlBreaker {

	private static final Logger log = LoggerFactory.getLogger(CrawlBreaker.class);

	private static final class State {
		int consecutiveBlocked;
		int trips;
		int successes;      // good pages in a row since the last blocked response
		Instant openUntil;
		Duration retryAfter;
	}

	private final IntSupplier threshold;
	private final Supplier<Duration> cooldown;
	private final Supplier<Duration> maxCooldown;
	private final Clock clock;
	private final IntSupplier successesToReset;
	private final Map<ChainId, State> states = new EnumMap<>(ChainId.class);

	public static final int DEFAULT_SUCCESSES_TO_RESET = 3;

	public CrawlBreaker(IntSupplier threshold, Supplier<Duration> cooldown, Supplier<Duration> maxCooldown, Clock clock) {
		this(threshold, cooldown, maxCooldown, clock, () -> DEFAULT_SUCCESSES_TO_RESET);
	}

	/** @param successesToReset consecutive good pages that clear the trip history (at least 1) */
	public CrawlBreaker(IntSupplier threshold, Supplier<Duration> cooldown, Supplier<Duration> maxCooldown, Clock clock,
			IntSupplier successesToReset) {
		this.successesToReset = successesToReset;
		this.threshold = threshold;
		this.cooldown = cooldown;
		this.maxCooldown = maxCooldown;
		this.clock = clock;
	}

	/** True while the chain is in its cool-down. The first call after it ended closes the breaker and logs it. */
	public synchronized boolean isOpen(ChainId chain) {
		State state = states.get(chain);
		if (state == null || state.openUntil == null) {
			return false;
		}
		if (clock.instant().isBefore(state.openUntil)) {
			return true;
		}
		log.warn("{}: crawl circuit breaker CLOSED after its cool-down; page loads resume (one more blocked response "
				+ "opens it again for a longer cool-down)", chain);
		state.openUntil = null;
		state.consecutiveBlocked = Math.max(0, threshold.getAsInt() - 1);      // half-open
		return false;
	}

	/**
	 * @return when the cool-down ends, or null when the breaker is closed (or its cool-down is over). Read-only: unlike
	 *         {@link #isOpen} it never closes the breaker or logs, so a log line cannot change the state.
	 */
	public synchronized Instant openUntil(ChainId chain) {
		State state = states.get(chain);
		return state != null && state.openUntil != null && clock.instant().isBefore(state.openUntil) ? state.openUntil
				: null;
	}

	/**
	 * A page was loaded normally: clears the consecutive-blocked count at once; the trip history (which doubles the
	 * cool-down) is cleared only after {@code successesToReset} good pages in a row.
	 */
	public synchronized void recordSuccess(ChainId chain) {
		if (isOpen(chain)) {
			return;      // a late report from a page requested before the trip does not close it
		}
		State state = states.get(chain);
		if (state != null) {
			state.consecutiveBlocked = 0;
			state.retryAfter = null;
			state.successes++;
			if (state.successes >= Math.max(1, successesToReset.getAsInt())) {
				state.trips = 0;
			}
		}
	}

	/**
	 * A page came back blocked.
	 *
	 * @param retryAfter the response's Retry-After, or null
	 * @return true when this response opened the breaker
	 */
	public synchronized boolean recordBlocked(ChainId chain, String reason, Duration retryAfter) {
		if (isOpen(chain)) {
			return false;
		}
		State state = states.computeIfAbsent(chain, c -> new State());
		state.successes = 0;
		state.consecutiveBlocked++;
		if (retryAfter != null && (state.retryAfter == null || retryAfter.compareTo(state.retryAfter) > 0)) {
			state.retryAfter = retryAfter;
		}
		if (state.consecutiveBlocked < Math.max(1, threshold.getAsInt())) {
			log.warn("{}: blocked response ({}), {} in a row; the circuit breaker opens at {}", chain, reason,
					state.consecutiveBlocked, Math.max(1, threshold.getAsInt()));
			return false;
		}
		state.trips++;
		Duration max = maxCooldown.get();
		Duration wait = cooldown.get();
		for (int i = 1; i < state.trips && wait.compareTo(max) < 0; i++) {
			wait = wait.multipliedBy(2);
		}
		if (state.retryAfter != null && state.retryAfter.compareTo(wait) > 0) {
			wait = state.retryAfter;
		}
		if (wait.compareTo(max) > 0) {
			wait = max;
		}
		state.openUntil = clock.instant().plus(wait);
		state.consecutiveBlocked = 0;
		state.retryAfter = null;
		log.warn("{}: crawl circuit breaker OPEN after {} consecutive blocked responses (last: {}). No page of this chain "
				+ "will be loaded, by the schedule or on demand, until {} (cool-down {}, consecutive trip #{}; in memory, "
				+ "a restart resets it)", chain, Math.max(1, threshold.getAsInt()), reason, state.openUntil, wait,
				state.trips);
		return true;
	}

	/** The breaker of one chain as the scraper sees it. */
	public CrawlBrake forChain(ChainId chain) {
		return new CrawlBrake() {
			@Override
			public boolean isOpen() {
				return CrawlBreaker.this.isOpen(chain);
			}

			@Override
			public void onSuccess() {
				recordSuccess(chain);
			}

			@Override
			public void onBlocked(String reason, Duration retryAfter) {
				recordBlocked(chain, reason, retryAfter);
			}
		};
	}
}
