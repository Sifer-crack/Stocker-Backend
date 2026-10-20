package com.stocker.pricing.ingest.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.CrawlBrake;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import com.stocker.pricing.ingest.parse.ChainPageTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What the loader tells the circuit breaker for each kind of response (no browser: Page and Response are mocks). */
class TemplatedPageLoaderStatusTest {

	private static final String URL = "https://www.newworld.co.nz/shop/category/milk";

	private TemplatedPageLoader loader;
	private Page page;
	private Response response;
	private CrawlBrake brake;

	@BeforeEach
	void setUp() {
		ChainPageTemplate template = ChainPageTemplate.foodstuffs(ChainId.NEWWORLD);
		LeanPageRouter router = new LeanPageRouter(ChainId.NEWWORLD, LeanPageOptions.OFF, "https://www.newworld.co.nz");
		loader = new TemplatedPageLoader(ChainId.NEWWORLD, template, router, new PageCountMonitor(), false);
		page = mock(Page.class);
		response = mock(Response.class);
		brake = mock(CrawlBrake.class);
		when(page.navigate(anyString())).thenReturn(response);
		when(page.title()).thenReturn("Fresh milk");
		Locator body = mock(Locator.class);
		when(body.innerText()).thenReturn("Fresh milk, nothing else");
		when(page.locator(anyString())).thenReturn(body);
	}

	private void pageShowsTiles(int n) {
		List<Map<String, Object>> cards = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			cards.add(Map.of("name", "Milk " + i));
		}
		when(page.evaluate(anyString(), any())).thenReturn(Map.of("cards", cards, "nav", false));
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 500, 502, 504 })
	void aStatusOf400OrMoreIsAFailedPageNotAGoodOneAndNotABlock(int status) {
		when(response.status()).thenReturn(status);
		pageShowsTiles(0);

		assertThatThrownBy(() -> loader.load(page, URL, brake)).isInstanceOf(PlaywrightException.class)
				.hasMessageContaining("HTTP " + status);

		verify(brake, never()).onSuccess();
		verify(brake, never()).onBlocked(anyString(), any());
	}

	@ParameterizedTest
	@ValueSource(ints = { 403, 429, 503 })
	void blockedStatusesStillTripTheBrake(int status) {
		when(response.status()).thenReturn(status);

		assertThatThrownBy(() -> loader.load(page, URL, brake)).isInstanceOf(PlaywrightException.class);

		verify(brake).onBlocked(anyString(), any());
		verify(brake, never()).onSuccess();
	}

	@Test
	void aPageWithZeroTilesIsNotAGoodPage() {
		when(response.status()).thenReturn(200);
		pageShowsTiles(0);

		TemplatedPageLoader.PageRead read = loader.load(page, URL, brake);

		assertThat(read.cards()).isEmpty();             // the scraper records this as a zero-result category
		verify(brake, never()).onSuccess();
		verify(brake, never()).onBlocked(anyString(), any());
	}

	@Test
	void aPageWithTilesIsAGoodPageAndEveryRequestIsAnnounced() {
		when(response.status()).thenReturn(200);
		pageShowsTiles(3);

		TemplatedPageLoader.PageRead read = loader.load(page, URL, brake);

		assertThat(read.cards()).hasSize(3);
		verify(brake).onPageRequested();
		verify(brake).onSuccess();
	}

	@Test
	void everyAttemptIsAnnouncedEvenWhenItFails() {
		when(response.status()).thenReturn(500);

		assertThatThrownBy(() -> loader.load(page, URL, brake)).isInstanceOf(PlaywrightException.class);

		verify(brake).onPageRequested();
	}
}
