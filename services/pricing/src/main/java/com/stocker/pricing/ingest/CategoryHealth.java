package com.stocker.pricing.ingest;

import com.stocker.pricing.ingest.model.ScrapeResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks, per chain and category page, how many scrapes in a row returned zero products. A page that keeps coming back
 * empty (renamed or removed category, changed markup) is quarantined: the scheduled crawl and the on-demand refresh
 * skip it, so it spends none of the capped daily page budget, until the quarantine period is over. It is then retried
 * once; a zero on that retry quarantines it again at once, a non-zero result clears the page.
 *
 * <p>Site-wide guard: when more than the configured share of the pages visited in one run (at least 4 pages) are
 * zero, the run is ignored entirely (nothing counted, nothing cleared), as that points at the site, not the pages.
 *
 * <p>Only a clean zero counts. A page that failed (scrape error, warning, never visited) neither counts as zero nor
 * clears the counter. State is in memory only: a restart forgets every quarantine, and each page then needs the
 * configured number of zero runs again.
 */
public class CategoryHealth {

	private static final Logger log = LoggerFactory.getLogger(CategoryHealth.class);

	/** A quarantined page, as reported by {@link #snapshot()}. */
	public record QuarantinedPage(ChainId chain, String url, Instant since, Instant until) {
	}

	private static final class State {
		int zeroRuns;
		Instant since;
		Instant until;      // non-null while quarantined (or expired and waiting for its retry)
	}

	private record Key(ChainId chain, String url) {
	}

	private final int zeroRunsBeforeQuarantine;
	private final Duration quarantinePeriod;
	private final double siteWideZeroShare;
	private final Clock clock;
	private final Map<Key, State> states = new HashMap<>();

	/** Runs visiting fewer pages than this never trip the site-wide guard. */
	static final int MIN_PAGES_FOR_GUARD = 4;

	public CategoryHealth(int zeroRunsBeforeQuarantine, Duration quarantinePeriod, Clock clock) {
		this(zeroRunsBeforeQuarantine, quarantinePeriod, 0.5, clock);
	}

	public CategoryHealth(int zeroRunsBeforeQuarantine, Duration quarantinePeriod, double siteWideZeroShare,
			Clock clock) {
		this.siteWideZeroShare = siteWideZeroShare;
		this.zeroRunsBeforeQuarantine = Math.max(1, zeroRunsBeforeQuarantine);
		this.quarantinePeriod = quarantinePeriod;
		this.clock = clock;
	}

	/**
	 * True while the page is quarantined. The first call after the period ended returns false, logs the retry and
	 * leaves the page one zero away from going back into quarantine.
	 */
	public synchronized boolean isQuarantined(ChainId chain, String url) {
		Key key = key(chain, url);
		State state = states.get(key);
		if (state == null || state.until == null) {
			return false;
		}
		if (clock.instant().isBefore(state.until)) {
			return true;
		}
		log.info("{}: quarantine over for {}, retrying it once", chain, key.url());
		state.until = null;
		state.since = null;
		state.zeroRuns = zeroRunsBeforeQuarantine - 1;   // a single further zero puts it straight back
		return false;
	}

	/** Records a clean zero-product scrape of the page. */
	public synchronized void recordZero(ChainId chain, String url) {
		Key key = key(chain, url);
		State state = states.computeIfAbsent(key, k -> new State());
		if (state.until != null) {
			return;      // already quarantined (e.g. an explicit on-demand call); keep the original window
		}
		state.zeroRuns++;
		if (state.zeroRuns >= zeroRunsBeforeQuarantine) {
			Instant now = clock.instant();
			state.since = now;
			state.until = now.plus(quarantinePeriod);
			log.warn("{}: {} returned zero products {} run(s) in a row, quarantined until {} (no page budget spent on it)",
					chain, key.url(), state.zeroRuns, state.until);
		}
	}

	/** Records a scrape that found products on the page: clears its counter and any quarantine. */
	public synchronized void recordSuccess(ChainId chain, String url) {
		states.remove(key(chain, url));
	}

	/**
	 * Records the outcome of one scrape call. Only pages the scraper actually visited are judged: those in
	 * {@code zeroResultCategoryUrls} count as zero, the other visited ones as success. A page that failed (not visited)
	 * or that is named in a warning is left untouched.
	 */
	public synchronized void record(ChainId chain, ScrapeResult result) {
		if (result == null || result.getCategoriesVisited() == null) {
			return;
		}
		List<String> zero = result.getZeroResultCategoryUrls() == null ? List.of() : result.getZeroResultCategoryUrls();
		List<String> warnings = result.getWarnings() == null ? List.of() : result.getWarnings();
		int visited = result.getCategoriesVisited().size();
		long zeros = result.getCategoriesVisited().stream().filter(zero::contains).count();
		if (visited >= MIN_PAGES_FOR_GUARD && (double) zeros / visited > siteWideZeroShare) {
			log.warn("{}: {} of {} visited pages ({}%) returned zero products, treating it as a site-wide problem "
					+ "(markup change, block page or outage); not counting these zeros toward quarantine",
					chain, zeros, visited, Math.round(100.0 * zeros / visited));
			return;
		}
		for (String url : result.getCategoriesVisited()) {
			if (warnings.stream().anyMatch(w -> w != null && w.contains(url))) {
				continue;
			}
			if (zero.contains(url)) {
				recordZero(chain, url);
			} else {
				recordSuccess(chain, url);
			}
		}
	}

	/** Read-only view of the pages currently quarantined (period not yet over), longest-waiting first. */
	public synchronized List<QuarantinedPage> snapshot() {
		Instant now = clock.instant();
		List<QuarantinedPage> pages = new ArrayList<>();
		states.forEach((key, state) -> {
			if (state.until != null && now.isBefore(state.until)) {
				pages.add(new QuarantinedPage(key.chain(), key.url(), state.since, state.until));
			}
		});
		pages.sort((a, b) -> a.since().compareTo(b.since()));
		return List.copyOf(pages);
	}

	private static Key key(ChainId chain, String url) {
		return new Key(chain, url.replaceAll("\\?pg=1$", ""));
	}
}
