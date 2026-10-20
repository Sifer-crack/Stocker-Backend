package com.stocker.pricing.refresh;

import com.stocker.pricing.ingest.ChainId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Woolworths category URLs are pasted from its sitemap (categories.xml); the path must survive parsing intact. */
class CategoryRouterWoolworthsUrlsTest {

	private static CategoryRouter routerWith(String sitemapText) {
		CategoryRouter router = new CategoryRouter();
		router.addWoolworthsUrls(sitemapText);
		return router;
	}

	@Test
	void aPathContainingLocIsNotCorrupted() {
		CategoryRouter router = routerWith(
				"<loc>https://www.woolworths.co.nz/shop/browse/fridge-deli/cheese/block-cheese</loc>");

		Optional<String> url = router.route(ChainId.WOOLWORTHS, "block cheese", null);

		assertThat(url).contains("https://www.woolworths.co.nz/shop/browse/fridge-deli/cheese/block-cheese");
	}

	@Test
	void wordsLikeLocalAndClockSurvive() {
		CategoryRouter router = routerWith(
				"<loc>https://www.woolworths.co.nz/shop/browse/pantry/local-honey</loc>\n"
						+ "<loc>https://www.woolworths.co.nz/shop/browse/pantry/clockwork-biscuits</loc>");

		assertThat(router.route(ChainId.WOOLWORTHS, "local honey", null))
				.contains("https://www.woolworths.co.nz/shop/browse/pantry/local-honey");
		assertThat(router.route(ChainId.WOOLWORTHS, "clockwork biscuits", null))
				.contains("https://www.woolworths.co.nz/shop/browse/pantry/clockwork-biscuits");
	}

	@Test
	void theRoutedUrlHasNoTrailingSlashOrMarkup() {
		CategoryRouter router = routerWith("<loc>https://www.woolworths.co.nz/shop/browse/fridge-deli/eggs-butter-spreads/butter</loc>");

		String url = router.route(ChainId.WOOLWORTHS, "butter", null).orElseThrow();

		assertThat(url).endsWith("/shop/browse/fridge-deli/eggs-butter-spreads/butter").doesNotContain("<").doesNotContain(">");
	}
}
