package com.stocker.pricing.ingest;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only hosts a chain's crawl may load pages from. A URL from the sitemap or from configuration that points
 * elsewhere (a poisoned sitemap, a typo, a redirect-style trick like {@code newworld.co.nz.evil.example}) is rejected
 * before any request is made: https only, and a host equal to or a subdomain of the chain's own registrable domain.
 */
public final class ChainDomains {

	private static final Logger log = LoggerFactory.getLogger(ChainDomains.class);

	private ChainDomains() {
	}

	public static String domain(ChainId chain) {
		return switch (chain) {
			case NEWWORLD -> "newworld.co.nz";
			case PAKNSAVE -> "paknsave.co.nz";
			case WOOLWORTHS -> "woolworths.co.nz";
		};
	}

	/** True for an https URL whose host is the chain's domain or one of its subdomains (no userinfo tricks). */
	public static boolean isOwnUrl(ChainId chain, String url) {
		if (url == null || url.isBlank()) {
			return false;
		}
		try {
			URI uri = new URI(url.trim());
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null) {
				return false;
			}
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			String domain = domain(chain);
			return host.equals(domain) || host.endsWith("." + domain);
		} catch (java.net.URISyntaxException e) {
			return false;
		}
	}

	/** The URLs that pass {@link #isOwnUrl}; every other one is logged at WARN and dropped. */
	public static List<String> onlyOwn(ChainId chain, List<String> urls, String source) {
		return urls.stream().filter(url -> {
			boolean ok = isOwnUrl(chain, url);
			if (!ok) {
				log.warn("{}: rejecting {} URL '{}': it must be https on {} (or a subdomain); it is not crawled", chain,
						source, url, domain(chain));
			}
			return ok;
		}).toList();
	}
}
