package com.stocker.pricing.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can move forward. */
class MutableClock extends Clock {

	private Instant now;

	MutableClock(Instant start) {
		this.now = start;
	}

	void advance(Duration d) {
		now = now.plus(d);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}
}
