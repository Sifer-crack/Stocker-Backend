package com.stocker.shopping.integration;

import com.stocker.shopping.application.FavoriteStoreNotFoundException;
import com.stocker.shopping.application.FavoriteStoreService;
import com.stocker.shopping.domain.FavoriteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Postgres + Flyway V4 + JPA. Skipped unless STOCKER_SHOPPING_DB_IT=true; needs a database at
 * localhost:5432/shopping (or STOCKER_DB_URL), stocker/stocker - e.g. a throwaway docker postgres.
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_SHOPPING_DB_IT", matches = "true")
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class FavoriteStorePostgresIT {

	@Autowired
	private FavoriteStoreService service;
	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.update("delete from favorite_stores");
	}

	@Test
	void favoritesRoundTripInOrderAndAreRemovable() {
		service.add("user-1", "paknsave", "ps:1");
		service.add("user-1", "woolworths", null);

		assertThat(service.list("user-1")).extracting(FavoriteStore::getChainId).containsExactly("paknsave", "woolworths");

		FavoriteStore first = service.list("user-1").get(0);
		service.remove("user-1", first.getId());
		assertThat(service.list("user-1")).hasSize(1);
		assertThatThrownBy(() -> service.remove("user-1", UUID.randomUUID())).isInstanceOf(FavoriteStoreNotFoundException.class);
	}

	@Test
	void savingTheWholeChainTwiceIsIdempotentEvenWithANullStoreId() {
		FavoriteStore a = service.add("user-1", "newworld", null);
		FavoriteStore b = service.add("user-1", "newworld", null);

		assertThat(b.getId()).isEqualTo(a.getId());
		assertThat(jdbc.queryForObject("select count(*) from favorite_stores", Integer.class)).isEqualTo(1);
	}

	@Test
	void theDatabaseItselfRejectsADuplicateWholeChainFavorite() {
		jdbc.update("insert into favorite_stores (id, user_id, chain_id, created_at) values (?, 'u', 'newworld', now())",
				UUID.randomUUID());

		assertThatThrownBy(() -> jdbc.update(
				"insert into favorite_stores (id, user_id, chain_id, created_at) values (?, 'u', 'newworld', now())",
				UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}
}
