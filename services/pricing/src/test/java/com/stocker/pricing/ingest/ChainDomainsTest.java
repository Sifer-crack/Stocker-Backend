package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChainDomainsTest {

	@Test
	void ownHttpsUrlsOfEveryChainPass() {
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://www.newworld.co.nz/shop/category/milk?pg=1")).isTrue();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://newworld.co.nz/x")).isTrue();
		assertThat(ChainDomains.isOwnUrl(ChainId.PAKNSAVE, "https://www.paknsave.co.nz/shop/category/milk")).isTrue();
		assertThat(ChainDomains.isOwnUrl(ChainId.WOOLWORTHS, "https://www.woolworths.co.nz/shop/browse/fridge-deli")).isTrue();
	}

	@Test
	void httpIsRejected() {
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "http://www.newworld.co.nz/x")).isFalse();
	}

	@Test
	void anotherChainsOrAnotherDomainIsRejected() {
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://www.paknsave.co.nz/x")).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://evil.example/x")).isFalse();
	}

	@Test
	void lookAlikeHostsAreRejected() {
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://newworld.co.nz.evil.example/x")).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://evilnewworld.co.nz/x")).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://www.newworld.co.nz@evil.example/x")).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "https://evil.example/?u=https://www.newworld.co.nz/")).isFalse();
	}

	@Test
	void garbageIsRejected() {
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, null)).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "")).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "not a url")).isFalse();
		assertThat(ChainDomains.isOwnUrl(ChainId.NEWWORLD, "//www.newworld.co.nz/x")).isFalse();
	}

	@Test
	void onlyOwnKeepsTheGoodOnesInOrder() {
		assertThat(ChainDomains.onlyOwn(ChainId.PAKNSAVE, List.of("https://www.paknsave.co.nz/a", "https://x.example/b",
				"https://www.paknsave.co.nz/c"), "test")).containsExactly("https://www.paknsave.co.nz/a",
						"https://www.paknsave.co.nz/c");
	}
}
