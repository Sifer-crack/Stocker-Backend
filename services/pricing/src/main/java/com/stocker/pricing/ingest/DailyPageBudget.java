package com.stocker.pricing.ingest;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * A hard cap on supermarket page loads per chain per Auckland day. The scheduled crawl and the on-demand refresh
 * both reserve their pages here first (and get none while the chain's circuit breaker is open), so no combination of runs and lookups can exceed what was approved. In memory:
 * a restart resets the count, which is acceptable because a restart is rare and the crawl pacing is slow.
 */
public class DailyPageBudget {

	/** Approved by the owner on 2026-10-20 (was 80). */
	public static final int DEFAULT_CAP_PER_CHAIN = 140;

	private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");

	private static final class Usage {
		LocalDate day;
		int used;
	}

	private final IntSupplier capPerChain;
	private final Clock clock;
	private final CrawlBreaker breaker;
	private final Map<ChainId, Usage> usage = new EnumMap<>(ChainId.class);

	public DailyPageBudget(int capPerChain, Clock clock) {
		this(() -> capPerChain, clock);
	}

	public DailyPageBudget(IntSupplier capPerChain, Clock clock) {
		this(capPerChain, clock, null);
	}

	/** @param breaker when given, a chain whose breaker is open is granted no page at all (cron, startup, on-demand alike) */
	public DailyPageBudget(IntSupplier capPerChain, Clock clock, CrawlBreaker breaker) {
		this.capPerChain = capPerChain;
		this.clock = clock;
		this.breaker = breaker;
	}

	/** True while the chain's circuit breaker is open: no page of it may be loaded. */
	public boolean isBlocked(ChainId chain) {
		return breaker != null && breaker.isOpen(chain);
	}

	/** @return how many of the {@code requested} pages may be loaded now (0 when the day's budget is used up) */
	public synchronized int reserve(ChainId chain, int requested) {
		if (requested <= 0 || isBlocked(chain)) {
			return 0;
		}
		int granted = Math.min(requested, remaining(chain));
		if (granted > 0) {
			today(chain).used += granted;
		}
		return granted;
	}

	public synchronized int remaining(ChainId chain) {
		return Math.max(0, capPerChain.getAsInt() - today(chain).used);
	}

	/** Returns pages that were reserved but not actually loaded. */
	public synchronized void giveBack(ChainId chain, int pages) {
		if (pages > 0) {
			Usage today = today(chain);
			today.used = Math.max(0, today.used - pages);
		}
	}

	private Usage today(ChainId chain) {
		LocalDate day = clock.instant().atZone(AUCKLAND).toLocalDate();
		Usage current = usage.computeIfAbsent(chain, c -> new Usage());
		if (!day.equals(current.day)) {
			current.day = day;
			current.used = 0;
		}
		return current;
	}
}
