package com.stocker.shopping.domain;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FavoriteStoreTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneOffset.UTC);

	@Test
	void createsAFavoriteForTheUser() {
		FavoriteStore store = FavoriteStore.create("user-1", "paknsave", "ps:42", NOW);

		assertThat(store.getId()).isNotNull();
		assertThat(store.getUserId()).isEqualTo("user-1");
		assertThat(store.getChainId()).isEqualTo("paknsave");
		assertThat(store.getStoreId()).isEqualTo("ps:42");
		assertThat(store.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void chainIsTrimmedAndLowerCased() {
		assertThat(FavoriteStore.create("u", "  NewWorld ", null, NOW).getChainId()).isEqualTo("newworld");
	}

	@Test
	void blankStoreIdMeansTheWholeChain() {
		assertThat(FavoriteStore.create("u", "woolworths", "  ", NOW).getStoreId()).isNull();
		assertThat(FavoriteStore.create("u", "woolworths", null, NOW).getStoreId()).isNull();
	}

	@Test
	void rejectsAnUnknownChain() {
		assertThatThrownBy(() -> FavoriteStore.create("u", "countdown", null, NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("countdown");
	}

	@Test
	void rejectsABlankChain() {
		assertThatThrownBy(() -> FavoriteStore.create("u", " ", null, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> FavoriteStore.create("u", null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void supportedChainsMatchPricing() {
		assertThat(FavoriteStore.SUPPORTED_CHAINS).containsExactlyInAnyOrder("newworld", "paknsave", "woolworths");
	}
}
