package com.stocker.pricing.service;

import com.stocker.pricing.service.fulfilment.FulfilmentProperties;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.ChainFees;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped application.yml keys bind, and with no fees configured every chain offers only in_store at 0. */
class FulfilmentPropertiesBindingTest {

	@Test
	void shippedDefaultsHaveNoInventedFeesAndOnlyInStoreAtZero() throws Exception {
		MutablePropertySources sources = new MutablePropertySources();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
		Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

		FulfilmentProperties properties = binder.bindOrCreate("app.fulfilment", FulfilmentProperties.class);

		assertThat(properties.getChains()).containsOnlyKeys("newworld", "paknsave", "woolworths");
		for (ChainFees fees : properties.getChains().values()) {
			assertThat(fees.getTravelCost()).isEqualByComparingTo("0");
			assertThat(fees.getDeliveryFee()).isNull();
			assertThat(fees.getClickAndCollectFee()).isNull();
			assertThat(fees.getDeliveryFreeOver()).isNull();
		}
	}
}
