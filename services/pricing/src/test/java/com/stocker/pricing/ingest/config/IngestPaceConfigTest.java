package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.ChainId;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** The crawl pace and the circuit-breaker settings: shipped defaults, env overrides and per-chain fallback. */
class IngestPaceConfigTest {

	private IngestProperties bind(Map<String, Object> env) throws IOException {
		MutablePropertySources sources = new MutablePropertySources();
		sources.addLast(new MapPropertySource("env", env));
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
				.forEach(sources::addLast);
		return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
				.bind("app.ingest", IngestProperties.class).get();
	}

	@Test
	void shippedDefaultIsA20To40SecondRandomPausePerPage() throws IOException {
		IngestProperties properties = bind(Map.of());

		assertThat(properties.getPageDelayMinMillis()).isEqualTo(20_000);
		assertThat(properties.getPageDelayMaxMillis()).isEqualTo(40_000);
		for (ChainId chain : ChainId.values()) {
			assertThat(properties.pageDelayMinMillis(chain)).isEqualTo(20_000);
			assertThat(properties.pageDelayMaxMillis(chain)).isEqualTo(40_000);
		}
	}

	@Test
	void classDefaultsAreTheSameSoAnUnboundBeanIsNotFaster() {
		IngestProperties properties = new IngestProperties();

		assertThat(properties.getPageDelayMinMillis()).isEqualTo(20_000);
		assertThat(properties.getPageDelayMaxMillis()).isEqualTo(40_000);
	}

	@Test
	void theSharedEnvNamesStillOverrideTheDefaults() throws IOException {
		IngestProperties properties = bind(Map.of("STOCKER_INGEST_PAGE_DELAY_MIN_MILLIS", "1000",
				"STOCKER_INGEST_PAGE_DELAY_MAX_MILLIS", "2000"));

		assertThat(properties.pageDelayMinMillis(ChainId.WOOLWORTHS)).isEqualTo(1000);
		assertThat(properties.pageDelayMaxMillis(ChainId.WOOLWORTHS)).isEqualTo(2000);
	}

	@Test
	void oneChainCanBeSlowedWithoutTouchingTheOthers() throws IOException {
		IngestProperties properties = bind(Map.of("STOCKER_INGEST_NEWWORLD_PAGE_DELAY_MIN_MILLIS", "60000",
				"STOCKER_INGEST_NEWWORLD_PAGE_DELAY_MAX_MILLIS", "90000"));

		assertThat(properties.pageDelayMinMillis(ChainId.NEWWORLD)).isEqualTo(60_000);
		assertThat(properties.pageDelayMaxMillis(ChainId.NEWWORLD)).isEqualTo(90_000);
		assertThat(properties.pageDelayMinMillis(ChainId.PAKNSAVE)).isEqualTo(20_000);
		assertThat(properties.pageDelayMaxMillis(ChainId.WOOLWORTHS)).isEqualTo(40_000);
	}

	@Test
	void aChainOverrideOfOnlyTheMinimumKeepsTheSharedMaximumButNeverBelowTheMinimum() {
		IngestProperties properties = new IngestProperties();
		properties.getPaknsave().setPageDelayMinMillis(50_000L);

		assertThat(properties.pageDelayMinMillis(ChainId.PAKNSAVE)).isEqualTo(50_000);
		assertThat(properties.pageDelayMaxMillis(ChainId.PAKNSAVE)).isEqualTo(50_000);
	}

	@Test
	void breakerDefaultsAreThreeBlocksSixHoursCappedAt24() throws IOException {
		IngestProperties properties = bind(Map.of());

		assertThat(properties.getBreakerBlockedThreshold()).isEqualTo(3);
		assertThat(properties.getBreakerCooldown()).isEqualTo(Duration.ofHours(6));
		assertThat(properties.getBreakerMaxCooldown()).isEqualTo(Duration.ofHours(24));
	}

	@Test
	void breakerSettingsCanBeOverriddenByEnv() throws IOException {
		IngestProperties properties = bind(Map.of("STOCKER_INGEST_BREAKER_BLOCKED_THRESHOLD", "5",
				"STOCKER_INGEST_BREAKER_COOLDOWN", "PT2H", "STOCKER_INGEST_BREAKER_MAX_COOLDOWN", "PT48H"));

		assertThat(properties.getBreakerBlockedThreshold()).isEqualTo(5);
		assertThat(properties.getBreakerCooldown()).isEqualTo(Duration.ofHours(2));
		assertThat(properties.getBreakerMaxCooldown()).isEqualTo(Duration.ofHours(48));
	}
}
