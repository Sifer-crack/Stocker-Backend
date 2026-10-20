package com.stocker.pricing.ingest.impl;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per browser context: aborts the requests a price page never needs (images, media, fonts, optionally stylesheets, and
 * third-party analytics / ads / tracking hosts) and counts what it saw per page.
 *
 * <p>Never blocks: the main-frame navigation, any request to the chain's own domain (first party) by host pattern, or
 * a script / XHR / fetch / document by resource type. A blocked request only ever loses a passive file or a tracker.
 */
final class LeanPageRouter {

	enum Decision {
		ALLOW, BLOCK_TYPE, BLOCK_HOST
	}

	/** What one page load requested: hosts x resource types, with how many of each were aborted. */
	static final class PageStats {
		private final Map<String, Map<String, int[]>> byHost = new ConcurrentHashMap<>();
		private final AtomicInteger total = new AtomicInteger();
		private final AtomicInteger blocked = new AtomicInteger();

		void record(String host, String type, boolean wasBlocked) {
			total.incrementAndGet();
			if (wasBlocked) {
				blocked.incrementAndGet();
			}
			int[] counts = byHost.computeIfAbsent(host, h -> new ConcurrentHashMap<>())
					.computeIfAbsent(type, t -> new int[2]);
			synchronized (counts) {
				counts[0]++;
				counts[1] += wasBlocked ? 1 : 0;
			}
		}

		int total() {
			return total.get();
		}

		int blocked() {
			return blocked.get();
		}

		/** "host (image 12/12 aborted, script 3), host2 (...)" - sorted, for the request log. */
		String describe() {
			Map<String, String> lines = new TreeMap<>();
			byHost.forEach((host, types) -> {
				StringBuilder sb = new StringBuilder();
				new TreeMap<>(types).forEach((type, c) -> {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					sb.append(type).append(' ').append(c[0]);
					if (c[1] > 0) {
						sb.append(c[1] == c[0] ? " (all aborted)" : " (" + c[1] + " aborted)");
					}
				});
				lines.put(host, host + " [" + sb + "]");
			});
			return String.join("; ", lines.values());
		}
	}

	private final LeanPageOptions options;
	private final String firstPartyDomain;
	private volatile PageStats stats = new PageStats();

	/** @param baseUrl the chain's own site, e.g. {@code https://www.newworld.co.nz}; its domain is first party */
	LeanPageRouter(ChainId chain, LeanPageOptions options, String baseUrl) {
		this.options = options;
		this.firstPartyDomain = registrableDomain(hostOf(baseUrl));
	}

	/** Installs the handler on the context; does nothing when neither blocking nor request logging is wanted. */
	void install(BrowserContext context) {
		if (!options.needsRouting()) {
			return;
		}
		context.route("**/*", route -> handle(route));
	}

	/** Starts counting a new page; call right before navigating. */
	PageStats beginPage() {
		PageStats fresh = new PageStats();
		stats = fresh;
		return fresh;
	}

	PageStats currentStats() {
		return stats;
	}

	private void handle(Route route) {
		Request request = route.request();
		String url = request.url();
		String type = request.resourceType();
		boolean mainNavigation = request.isNavigationRequest() && request.frame().parentFrame() == null;
		Decision decision = options.enabled() ? decide(url, type, mainNavigation) : Decision.ALLOW;
		stats.record(hostOf(url), type, decision != Decision.ALLOW);
		try {
			if (decision == Decision.ALLOW) {
				route.resume();
			} else {
				route.abort();
			}
		} catch (PlaywrightException e) {
			// The page moved on (navigation / close) before the request was settled: nothing left to do.
		}
	}

	/** Pure decision, public to the package for tests. */
	Decision decide(String url, String resourceType, boolean mainNavigation) {
		if (mainNavigation || !options.enabled()) {
			return Decision.ALLOW;
		}
		String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			return Decision.ALLOW;      // data:, blob:, about:
		}
		String type = resourceType == null ? "" : resourceType.toLowerCase(Locale.ROOT);
		if (options.blockResourceTypes().contains(type)) {
			return Decision.BLOCK_TYPE;
		}
		String host = hostOf(url);
		if (isFirstParty(host)) {
			return Decision.ALLOW;
		}
		for (String pattern : options.blockHostPatterns()) {
			if (host.contains(pattern)) {
				return Decision.BLOCK_HOST;
			}
		}
		return Decision.ALLOW;
	}

	boolean isFirstParty(String host) {
		return !firstPartyDomain.isEmpty() && (host.equals(firstPartyDomain) || host.endsWith("." + firstPartyDomain));
	}

	static String hostOf(String url) {
		try {
			String host = URI.create(url).getHost();
			return host == null ? "" : host.toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	/** "www.newworld.co.nz" -> "newworld.co.nz"; an IP or single label stays as it is. */
	static String registrableDomain(String host) {
		return host.startsWith("www.") ? host.substring(4) : host;
	}
}
