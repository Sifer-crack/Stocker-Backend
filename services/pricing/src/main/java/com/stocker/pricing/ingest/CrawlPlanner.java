package com.stocker.pricing.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which category pages a daily run loads, and in what order, when the budget may not cover them all.
 * Priority pages (the common products and staples) always come first; the remaining slots go to a window of the
 * other pages that moves on every day. Pages crawled within the freshness window go to the back of their group, so
 * they are the first to be cut when the daily budget runs short.
 */
public final class CrawlPlanner {

	private CrawlPlanner() {
	}

	public static List<String> plan(List<String> priority, List<String> others, Map<String, Instant> lastCrawled,
			Duration freshness, Instant now, int window, long dayNumber) {
		Set<String> allPriority = new LinkedHashSet<>(priority);
		// When even the priority pages do not all fit, they rotate day by day like any other list, so none starves.
		Set<String> priorityPages = window > 0 && allPriority.size() > window
				? new LinkedHashSet<>(IngestScheduler.selectCategories(List.copyOf(allPriority), window, dayNumber))
				: allPriority;
		List<String> rest = others.stream().filter(url -> !allPriority.contains(url)).distinct().toList();
		int restSlots = Math.max(0, window - priorityPages.size());

		List<String> restStale = rest.stream().filter(url -> !isFresh(url, lastCrawled, freshness, now)).toList();
		List<String> restFresh = rest.stream().filter(url -> isFresh(url, lastCrawled, freshness, now)).toList();
		List<String> restPicked = new ArrayList<>(IngestScheduler.selectCategories(restStale, restSlots, dayNumber));
		if (restPicked.size() < restSlots) {
			restPicked.addAll(restFresh.subList(0, Math.min(restFresh.size(), restSlots - restPicked.size())));
		}

		List<String> ordered = new ArrayList<>();
		List<String> fresh = new ArrayList<>();
		for (String url : priorityPages) {
			(isFresh(url, lastCrawled, freshness, now) ? fresh : ordered).add(url);
		}
		for (String url : restPicked) {
			(isFresh(url, lastCrawled, freshness, now) ? fresh : ordered).add(url);
		}
		ordered.addAll(fresh);
		return window > 0 && ordered.size() > window ? List.copyOf(ordered.subList(0, window)) : List.copyOf(ordered);
	}

	private static boolean isFresh(String url, Map<String, Instant> lastCrawled, Duration freshness, Instant now) {
		Instant last = lastCrawled.get(url);
		return last != null && Duration.between(last, now).compareTo(freshness) < 0;
	}
}
