package com.stocker.shopping.application;

import com.stocker.shopping.domain.FavoriteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** How favorite stores are used: real service, in-memory repository, a moving clock. */
@Tag("service")
class FavoriteStoreScenarioTest {

	private final InMemoryFavoriteStoreRepository repository = new InMemoryFavoriteStoreRepository();
	private Instant now = Instant.parse("2026-10-19T00:00:00Z");
	private FavoriteStoreService service;

	@BeforeEach
	void setUp() {
		Clock movingClock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now;
			}
		};
		service = new FavoriteStoreService(repository, movingClock);
	}

	@Test
	void aUserBuildsUpAFavoritesSectionInTheOrderTheySavedThem() {
		service.add("user-1", "paknsave", null);
		now = now.plus(Duration.ofMinutes(5));
		service.add("user-1", "woolworths", null);

		List<FavoriteStore> favorites = service.list("user-1");

		assertThat(favorites).extracting(FavoriteStore::getChainId).containsExactly("paknsave", "woolworths");
	}

	@Test
	void savingTwiceDoesNotCreateADuplicate() {
		service.add("user-1", "newworld", "nw:1");
		service.add("user-1", "newworld", "nw:1");
		service.add("user-1", "NewWorld", "nw:1");

		assertThat(service.list("user-1")).hasSize(1);
	}

	@Test
	void usersNeverSeeOrRemoveEachOthersFavorites() {
		FavoriteStore mine = service.add("user-1", "newworld", null);
		service.add("user-2", "paknsave", null);

		assertThat(service.list("user-1")).containsExactly(mine);
		assertThatThrownBy(() -> service.remove("user-2", mine.getId())).isInstanceOf(FavoriteStoreNotFoundException.class);
		assertThat(service.list("user-1")).hasSize(1);
	}

	@Test
	void removedFavoritesDisappearAndCanBeSavedAgain() {
		FavoriteStore store = service.add("user-1", "newworld", null);

		service.remove("user-1", store.getId());
		assertThat(service.list("user-1")).isEmpty();

		service.add("user-1", "newworld", null);
		assertThat(service.list("user-1")).hasSize(1);
		assertThat(repository.size()).isEqualTo(1);
		assertThat(UUID.randomUUID()).isNotEqualTo(store.getId());
	}
}
