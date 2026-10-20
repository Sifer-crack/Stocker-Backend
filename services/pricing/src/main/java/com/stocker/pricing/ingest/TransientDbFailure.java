package com.stocker.pricing.ingest;

import java.io.EOFException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Tells a dropped database connection (serverless Postgres restarts its compute and cuts live connections) from a
 * real data error. Only the former is worth retrying the same rows for.
 */
final class TransientDbFailure {

	private TransientDbFailure() {
	}

	/** True when anything in the cause chain is SQLSTATE class 08, an EOFException or a "connection closed/broken" message. */
	static boolean isTransient(Throwable failure) {
		int depth = 0;
		for (Throwable t = failure; t != null && depth < 20; t = t.getCause(), depth++) {
			if (t instanceof EOFException) {
				return true;
			}
			for (SQLException sql = t instanceof SQLException s ? s : null; sql != null; sql = sql.getNextException()) {
				if (sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
					return true;
				}
			}
			String message = t.getMessage();
			if (message != null) {
				String lower = message.toLowerCase(Locale.ROOT);
				if (lower.contains("connection closed") || lower.contains("connection is closed")
						|| lower.contains("has been closed") || lower.contains("marked as broken")
						|| lower.contains("connection reset") || lower.contains("terminating connection")) {
					return true;
				}
			}
		}
		return false;
	}
}
