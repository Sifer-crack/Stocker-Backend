package com.stocker.pricing.ingest.impl;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.impl.LeanPageRouter.Decision;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeanPageRouterTest {

	private static final List<String> TRACKERS = List.of("google-analytics", "googletagmanager", "doubleclick", "hotjar");

	private LeanPageRouter router(boolean enabled, Set<String> types) {
		return new LeanPageRouter(ChainId.NEWWORLD, new LeanPageOptions(enabled, types, TRACKERS, false),
				"https://www.newworld.co.nz");
	}

	@Test
	void imagesMediaAndFontsAreBlockedByTypeWhateverTheHost() {
		LeanPageRouter router = router(true, Set.of("image", "media", "font"));

		assertThat(router.decide("https://www.newworld.co.nz/a.png", "image", false)).isEqualTo(Decision.BLOCK_TYPE);
		assertThat(router.decide("https://cdn.example/a.woff2", "font", false)).isEqualTo(Decision.BLOCK_TYPE);
		assertThat(router.decide("https://cdn.example/a.mp4", "media", false)).isEqualTo(Decision.BLOCK_TYPE);
	}

	@Test
	void stylesheetsAreAllowedUnlessConfigured() {
		assertThat(router(true, Set.of("image", "media", "font")).decide("https://www.newworld.co.nz/a.css", "stylesheet", false))
				.isEqualTo(Decision.ALLOW);
		assertThat(router(true, Set.of("stylesheet")).decide("https://www.newworld.co.nz/a.css", "stylesheet", false))
				.isEqualTo(Decision.BLOCK_TYPE);
	}

	@Test
	void thirdPartyTrackerHostsAreBlockedByPattern() {
		LeanPageRouter router = router(true, Set.of("image"));

		assertThat(router.decide("https://www.google-analytics.com/collect", "script", false)).isEqualTo(Decision.BLOCK_HOST);
		assertThat(router.decide("https://www.googletagmanager.com/gtm.js?id=1", "script", false)).isEqualTo(Decision.BLOCK_HOST);
		assertThat(router.decide("https://stats.g.doubleclick.net/x", "xhr", false)).isEqualTo(Decision.BLOCK_HOST);
		assertThat(router.decide("https://static.HOTJAR.com/c.js", "script", false)).isEqualTo(Decision.BLOCK_HOST);
	}

	@Test
	void theChainsOwnDomainIsNeverBlockedByHostPattern() {
		LeanPageRouter router = new LeanPageRouter(ChainId.NEWWORLD,
				new LeanPageOptions(true, Set.of("image"), List.of("newworld", "api"), false), "https://www.newworld.co.nz");

		assertThat(router.decide("https://www.newworld.co.nz/api/products", "xhr", false)).isEqualTo(Decision.ALLOW);
		assertThat(router.decide("https://api-prod.newworld.co.nz/search", "fetch", false)).isEqualTo(Decision.ALLOW);
		assertThat(router.decide("https://newworld.co.nz/app.js", "script", false)).isEqualTo(Decision.ALLOW);
		// a look-alike domain is NOT first party
		assertThat(router.decide("https://evilnewworld.co.nz/app.js", "script", false)).isEqualTo(Decision.BLOCK_HOST);
	}

	@Test
	void theMainNavigationIsNeverBlocked() {
		LeanPageRouter router = router(true, Set.of("image"));

		assertThat(router.decide("https://www.google-analytics.com/", "document", true)).isEqualTo(Decision.ALLOW);
	}

	@Test
	void scriptsXhrAndDocumentsOfUnknownHostsAreAllowed() {
		LeanPageRouter router = router(true, Set.of("image", "media", "font"));

		assertThat(router.decide("https://cdn.somevendor.com/bundle.js", "script", false)).isEqualTo(Decision.ALLOW);
		assertThat(router.decide("https://api.somevendor.com/products", "fetch", false)).isEqualTo(Decision.ALLOW);
		assertThat(router.decide("https://frame.somevendor.com/", "document", false)).isEqualTo(Decision.ALLOW);
		assertThat(router.decide("data:image/png;base64,AAA", "image", false)).isEqualTo(Decision.ALLOW);
	}

	@Test
	void disabledMeansEverythingIsAllowed() {
		LeanPageRouter router = router(false, Set.of("image"));

		assertThat(router.decide("https://www.google-analytics.com/collect", "image", false)).isEqualTo(Decision.ALLOW);
	}

	@Test
	void optionsNeverAllowBlockingActiveContentTypes() {
		LeanPageOptions options = new LeanPageOptions(true, Set.of("script", "xhr", "fetch", "document", "IMAGE"), List.of(), false);

		assertThat(options.blockResourceTypes()).containsExactly("image");
	}

	@Test
	void statsDescribeHostsTypesAndAbortedCounts() {
		LeanPageRouter router = router(true, Set.of("image"));
		LeanPageRouter.PageStats stats = router.beginPage();
		stats.record("cdn.example", "image", true);
		stats.record("cdn.example", "image", true);
		stats.record("www.newworld.co.nz", "script", false);

		assertThat(stats.total()).isEqualTo(3);
		assertThat(stats.blocked()).isEqualTo(2);
		assertThat(stats.describe()).contains("cdn.example [image 2 (all aborted)]").contains("www.newworld.co.nz [script 1]");
	}
}
