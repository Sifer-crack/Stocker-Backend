package com.stocker.shopping.application.model;

import java.util.Locale;

/** Which path produced a comparison update: the immediate gRPC response, or a later Kafka backfill. */
public enum UpdateSource {

	SYNC,
	BACKFILL;

	public String wire() {
		return name().toLowerCase(Locale.ROOT);
	}
}
