package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.ChainId;
import com.stocker.pricing.ingest.model.LeanPageOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestLeanPropertiesTest {

	@Test
	void leanPagesAreOffByDefaultAndTheRetryBackoffDefaultsToThreeRetries() {
		IngestProperties props = new IngestProperties();

		assertThat(props.isLeanPages()).isFalse();
		assertThat(props.leanOptions(ChainId.NEWWORLD).enabled()).isFalse();
		assertThat(props.getPersistRetryBackoffMillis()).containsExactly(2000L, 5000L, 10000L);
	}

	@Test
	void defaultsBlockImagesMediaFontsAndTrackersButNotStylesheets() {
		IngestProperties on = new IngestProperties();
		on.setLeanPages(true);
		LeanPageOptions options = on.leanOptions(ChainId.NEWWORLD);

		assertThat(options.enabled()).isTrue();
		assertThat(options.blockResourceTypes()).containsExactlyInAnyOrder("image", "media", "font");
		assertThat(options.blockHostPatterns()).contains("google-analytics", "googletagmanager", "doubleclick", "hotjar");
		assertThat(options.logRequests()).isFalse();
	}

	@Test
	void theKillSwitchTurnsEveryChainOff() {
		IngestProperties props = new IngestProperties();
		props.setLeanPages(false);

		for (ChainId chain : ChainId.values()) {
			assertThat(props.leanOptions(chain).enabled()).isFalse();
		}
	}

	@Test
	void oneChainCanBeSwitchedOffAndOverrideItsLists() {
		IngestProperties props = new IngestProperties();
		props.setLeanPages(true);
		props.getPaknsave().getLean().setEnabled(false);
		props.getWoolworths().getLean().setBlockResourceTypes(List.of("image"));
		props.getWoolworths().getLean().setBlockHostPatterns(List.of("only-this"));

		assertThat(props.leanOptions(ChainId.PAKNSAVE).enabled()).isFalse();
		assertThat(props.leanOptions(ChainId.NEWWORLD).enabled()).isTrue();
		assertThat(props.leanOptions(ChainId.WOOLWORTHS).blockResourceTypes()).containsExactly("image");
		assertThat(props.leanOptions(ChainId.WOOLWORTHS).blockHostPatterns()).containsExactly("only-this");
	}

	@Test
	void requestLoggingIsASeparateSwitch() {
		IngestProperties props = new IngestProperties();
		props.setLogRequests(true);

		assertThat(props.leanOptions(ChainId.NEWWORLD).logRequests()).isTrue();
		assertThat(props.leanOptions(ChainId.NEWWORLD).needsRouting()).isTrue();
	}
}
