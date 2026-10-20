package com.stocker.pricing.service;

import com.stocker.pricing.service.fulfilment.FulfilmentMethod;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.ChainFees;
import com.stocker.pricing.service.fulfilment.FulfilmentProperties.Tier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/** The SHIPPED application.yml parses to exactly the owner-supplied 2026 fee ladders. */
class FulfilmentPropertiesBindingTest {

	private static FulfilmentProperties shipped() throws Exception {
		MutablePropertySources sources = new MutablePropertySources();
		new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")).forEach(sources::addLast);
		Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
		return binder.bindOrCreate("app.fulfilment", FulfilmentProperties.class);
	}

	private static void assertLadder(List<Tier> ladder, String... fromFeePairs) {
		assertThat(ladder).hasSize(fromFeePairs.length / 2);
		for (int i = 0; i < fromFeePairs.length; i += 2) {
			assertThat(ladder.get(i / 2).getFrom()).isEqualByComparingTo(fromFeePairs[i]);
			assertThat(ladder.get(i / 2).getFee()).isEqualByComparingTo(fromFeePairs[i + 1]);
		}
	}

	@Test
	void regionsMapToIslands() throws Exception {
		FulfilmentProperties p = shipped();
		assertThat(p.getRegionIslands()).containsOnly(
				org.assertj.core.api.Assertions.entry("auckland", "north"),
				org.assertj.core.api.Assertions.entry("wellington", "north"),
				org.assertj.core.api.Assertions.entry("christchurch", "south"));
	}

	@Test
	void newWorldMatchesTheOwnersLadders() throws Exception {
		ChainFees nw = shipped().forChain("newworld");
		assertLadder(nw.getDelivery(), "0", "14.00", "200", "9.00");
		assertLadder(nw.getClickAndCollect(), "0", "5.00", "50", "0.00");
		assertThat(nw.getIslands()).containsOnlyKeys("south");
		assertLadder(nw.getIslands().get("south").getDelivery(), "0", "15.00", "200", "10.00");
		assertLadder(nw.getIslands().get("south").getClickAndCollect(), "0", "3.50");
	}

	@Test
	void paknsaveOffersOnlyClickAndCollect() throws Exception {
		ChainFees ps = shipped().forChain("paknsave");
		assertThat(ps.getDelivery()).isNullOrEmpty();
		assertLadder(ps.getClickAndCollect(), "0", "5.00");
		assertThat(ps.getIslands()).isEmpty();
	}

	@Test
	void woolworthsMatchesTheOwnersLadders() throws Exception {
		ChainFees w = shipped().forChain("woolworths");
		assertLadder(w.getDelivery(), "0", "14.00", "200", "9.00");
		assertLadder(w.getClickAndCollect(), "0", "3.50");
		assertThat(w.getIslands()).isEmpty();
	}

	@Test
	void noTravelCostKeyRemainsInTheShippedYaml() throws Exception {
		String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(),
				java.nio.charset.StandardCharsets.UTF_8);
		assertThat(yaml).doesNotContainIgnoringCase("travel");
	}

	@Test
	void onlyTheThreeChainsAreConfiguredAndShippedValuesResolveThroughFeeFor() throws Exception {
		FulfilmentProperties p = shipped();
		assertThat(p.getChains()).containsOnlyKeys("newworld", "paknsave", "woolworths");
		assertThat(p.forChain("newworld").feeFor(FulfilmentMethod.CLICK_AND_COLLECT, new java.math.BigDecimal("50.00"), p.islandOf("Auckland")))
				.isEqualByComparingTo("0");
	}
}
