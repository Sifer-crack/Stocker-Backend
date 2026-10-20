package com.stocker.shopping.application;

import com.stocker.shopping.application.port.FavoriteStoreRepository;
import com.stocker.shopping.domain.FavoriteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FavoriteStoreServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");

	private FavoriteStoreRepository repository;
	private FavoriteStoreService service;

	@BeforeEach
	void setUp() {
		repository = mock(FavoriteStoreRepository.class);
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		service = new FavoriteStoreService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	// acceptance: selecting "save as favorite" adds the store to the user's favorites
	@Test
	void addSavesANewFavoriteForTheUser() {
		FavoriteStore saved = service.add("user-1", "paknsave", "ps:42");

		ArgumentCaptor<FavoriteStore> captor = ArgumentCaptor.forClass(FavoriteStore.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue()).isSameAs(saved);
		assertThat(saved.getUserId()).isEqualTo("user-1");
		assertThat(saved.getChainId()).isEqualTo("paknsave");
		assertThat(saved.getStoreId()).isEqualTo("ps:42");
		assertThat(saved.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
	}

	@Test
	void addNormalisesTheChainBeforeLookingForADuplicate() {
		service.add("user-1", " PAKNSAVE ", null);

		verify(repository).findByUserIdAndChainIdAndStoreId("user-1", "paknsave", null);
	}

	@Test
	void savingTheSameStoreTwiceReturnsTheExistingFavorite() {
		FavoriteStore existing = FavoriteStore.create("user-1", "newworld", null, OffsetDateTime.now(ZoneOffset.UTC));
		when(repository.findByUserIdAndChainIdAndStoreId("user-1", "newworld", null)).thenReturn(Optional.of(existing));

		assertThat(service.add("user-1", "newworld", null)).isSameAs(existing);
		verify(repository, never()).save(any());
	}

	@Test
	void anotherStoreOfTheSameChainIsASeparateFavorite() {
		service.add("user-1", "newworld", "nw:1");
		service.add("user-1", "newworld", "nw:2");

		verify(repository).findByUserIdAndChainIdAndStoreId("user-1", "newworld", "nw:1");
		verify(repository).findByUserIdAndChainIdAndStoreId("user-1", "newworld", "nw:2");
	}

	@Test
	void rejectsAnUnknownChainWithoutSaving() {
		assertThatThrownBy(() -> service.add("user-1", "countdown", null)).isInstanceOf(IllegalArgumentException.class);
		verify(repository, never()).save(any());
	}

	@Test
	void listReturnsTheUsersFavorites() {
		List<FavoriteStore> rows = List.of(FavoriteStore.create("user-1", "newworld", null, OffsetDateTime.now(ZoneOffset.UTC)));
		when(repository.findByUserId("user-1")).thenReturn(rows);

		assertThat(service.list("user-1")).isEqualTo(rows);
	}

	@Test
	void removeDeletesTheUsersOwnFavorite() {
		FavoriteStore existing = FavoriteStore.create("user-1", "newworld", null, OffsetDateTime.now(ZoneOffset.UTC));
		when(repository.findByIdAndUserId(existing.getId(), "user-1")).thenReturn(Optional.of(existing));

		service.remove("user-1", existing.getId());

		verify(repository).delete(existing);
	}

	@Test
	void removingSomeoneElsesOrAnUnknownFavoriteIsNotFound() {
		UUID id = UUID.randomUUID();
		when(repository.findByIdAndUserId(id, "user-1")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.remove("user-1", id)).isInstanceOf(FavoriteStoreNotFoundException.class);
		verify(repository, never()).delete(any());
	}
}
