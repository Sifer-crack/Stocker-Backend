package com.stocker.pricing.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.EOFException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class TransientDbFailureTest {

	@Test
	void sqlStateClass08IsTransientEvenWhenWrapped() {
		SQLException sql = new SQLException("This connection has been closed.", "08003");
		assertThat(TransientDbFailure.isTransient(new RuntimeException("could not execute batch", sql))).isTrue();
		assertThat(TransientDbFailure.isTransient(new SQLException("x", "08006"))).isTrue();
	}

	@Test
	void eofAndConnectionClosedMessagesAreTransient() {
		assertThat(TransientDbFailure.isTransient(new RuntimeException("io", new EOFException()))).isTrue();
		assertThat(TransientDbFailure.isTransient(new IllegalStateException("Connection closed"))).isTrue();
		assertThat(TransientDbFailure.isTransient(
				new RuntimeException("HikariPool-1 - Connection org.postgresql.jdbc.PgConnection@1 marked as broken"))).isTrue();
	}

	@Test
	void dataErrorsAreNotTransient() {
		assertThat(TransientDbFailure.isTransient(new SQLException("duplicate key", "23505"))).isFalse();
		assertThat(TransientDbFailure.isTransient(new IllegalArgumentException("bad row"))).isFalse();
		assertThat(TransientDbFailure.isTransient(null)).isFalse();
	}
}
