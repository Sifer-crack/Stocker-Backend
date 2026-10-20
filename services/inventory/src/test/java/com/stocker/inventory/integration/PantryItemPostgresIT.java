package com.stocker.inventory.integration;

import com.stocker.inventory.application.PantryItemService;
import com.stocker.inventory.domain.PantryItem;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres + Flyway + JPA. Skipped unless STOCKER_INVENTORY_DB_IT=true; needs STOCKER_DB_URL, STOCKER_DB_USER
 * and STOCKER_DB_PASSWORD (inventory has no datasource defaults), e.g. a throwaway docker postgres. Never point
 * this at the shared Neon database.
 */
@EnabledIfEnvironmentVariable(named = "STOCKER_INVENTORY_DB_IT", matches = "true")
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class PantryItemPostgresIT {

	@Autowired
	private PantryItemService service;
	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void expiryRoundTripsThroughFlywayAndJpa() {
		UUID userId = UUID.randomUUID();
		LocalDate expiry = LocalDate.now().plusDays(2);

		PantryItem added = service.addPantryItem(userId, UUID.randomUUID(), 2, expiry, 3);
		PantryItem reloaded = service.getPantryItems(userId).get(0);

		assertThat(reloaded.getPantry_item_id()).isEqualTo(added.getPantry_item_id());
		assertThat(reloaded.getExpirationDate()).isEqualTo(expiry);
		assertThat(reloaded.getNotifyDaysBefore()).isEqualTo(3);
		assertThat(service.findDueForNotification(LocalDate.now())).extracting(PantryItem::getPantry_item_id)
				.contains(added.getPantry_item_id());

		service.updateExpiry(userId, added.getPantry_item_id(), null, null);
		assertThat(service.findDueForNotification(LocalDate.now())).extracting(PantryItem::getPantry_item_id)
				.doesNotContain(added.getPantry_item_id());
		assertThat(service.updatePantryItem(userId, added.getPantry_item_id(), 5).getQuantity()).isEqualTo(5);
	}

	@Test
	void checkConstraintRejectsANegativeThresholdEvenIfTheServiceIsBypassed() {
		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
				() -> jdbc.update("insert into pantry_items (user_id, product_id, quantity, notify_days_before) "
						+ "values (?, ?, 1, -1)", UUID.randomUUID(), UUID.randomUUID()));
	}

	/**
	 * The shared Neon database already has a hand-made pantry_items table and no Flyway history for inventory.
	 * Flyway must baseline at version 1 (skipping the CREATE TABLE) and still add the expiry columns.
	 */
	@Test
	void anExistingHandMadeTableIsBaselinedNotRecreated() {
		String url = System.getenv("STOCKER_DB_URL");
		DriverManagerDataSource dataSource = new DriverManagerDataSource(url, System.getenv("STOCKER_DB_USER"),
				System.getenv("STOCKER_DB_PASSWORD"));
		JdbcTemplate admin = new JdbcTemplate(dataSource);
		admin.execute("drop schema if exists it_baseline cascade");
		admin.execute("create schema it_baseline");
		admin.execute("create table it_baseline.pantry_items (pantry_item_id uuid primary key, user_id uuid not null, "
				+ "product_id uuid not null, quantity integer not null, created_at timestamptz default now())");
		admin.update("insert into it_baseline.pantry_items (pantry_item_id, user_id, product_id, quantity) "
				+ "values (?, ?, ?, 7)", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		try {
			Flyway.configure().dataSource(dataSource).schemas("it_baseline")
					.table("flyway_schema_history_inventory").baselineOnMigrate(true).baselineVersion("1")
					.locations("classpath:db/migration").load().migrate();

			List<String> columns = admin.queryForList("select column_name from information_schema.columns "
					+ "where table_schema = 'it_baseline' and table_name = 'pantry_items'", String.class);
			assertThat(columns).contains("expiration_date", "notify_days_before");
			assertThat(admin.queryForObject("select quantity from it_baseline.pantry_items", Integer.class)).isEqualTo(7);
			assertThat(admin.queryForList("select type from it_baseline.flyway_schema_history_inventory order by installed_rank",
					String.class)).containsExactly("BASELINE", "SQL");
		}
		finally {
			admin.execute("drop schema if exists it_baseline cascade");
		}
	}
}
