package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.stocker.pricing.ingest.config.IngestProperties;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** The shipped defaults: nothing reaches a supermarket without an explicit opt-in. */
class IngestDefaultsTest {

	private static Properties yaml() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		return yaml.getObject();
	}

	@Test
	void applicationYmlTurnsTheCrawlOffByDefault() {
		assertThat(yaml().getProperty("app.ingest.enabled")).isEqualTo("${STOCKER_INGEST_ENABLED:false}");
	}

	@Test
	void theCrawlIsOffAndDryInTheProperties() {
		IngestProperties properties = new IngestProperties();
		assertThat(properties.isEnabled()).isFalse();
		assertThat(properties.isDryRun()).isTrue();
		assertThat(properties.isRunOnStartup()).isFalse();
	}

	@Test
	void onDemandRefreshDefaultsAreSmall() {
		PricingCacheProperties.OnDemand onDemand = new PricingCacheProperties().getOnDemand();
		assertThat(onDemand.getMaxPagesPerCategory()).isEqualTo(2);
		assertThat(onDemand.getKeepDailyBudgetShare()).isEqualTo(0.3);
		assertThat(yaml().getProperty("app.pricing.on-demand.max-pages-per-category"))
				.isEqualTo("${STOCKER_PRICING_ON_DEMAND_MAX_PAGES_PER_CATEGORY:2}");
		assertThat(yaml().getProperty("app.pricing.on-demand.keep-daily-budget-share"))
				.isEqualTo("${STOCKER_PRICING_ON_DEMAND_KEEP_BUDGET_SHARE:0.3}");
	}

	@Test
	void theBreakerNeedsThreeGoodPagesInARowByDefault() {
		assertThat(new IngestProperties().getBreakerSuccessesToReset()).isEqualTo(3);
	}
}
