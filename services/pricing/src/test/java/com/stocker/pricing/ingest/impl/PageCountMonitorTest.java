package com.stocker.pricing.ingest.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageCountMonitorTest {

	@Test
	void theFirstSightingOfAUrlNeverWarns() {
		assertThat(new PageCountMonitor().record("u", 5)).isEmpty();
	}

	@Test
	void aDropBelowEightyPercentWarnsAndNamesTheCounts() {
		PageCountMonitor monitor = new PageCountMonitor();
		monitor.record("u", 179);

		assertThat(monitor.record("u", 100)).hasValueSatisfying(w -> assertThat(w).contains("179").contains("100").contains("u"));
	}

	@Test
	void exactlyEightyPercentAndSmallChangesDoNotWarn() {
		PageCountMonitor monitor = new PageCountMonitor();
		monitor.record("u", 100);
		assertThat(monitor.record("u", 80)).isEmpty();
		assertThat(monitor.record("u", 60)).isPresent();      // 60 < 0.8 * 80
	}

	@Test
	void growthAndOtherUrlsAreIgnored() {
		PageCountMonitor monitor = new PageCountMonitor();
		monitor.record("a", 10);
		assertThat(monitor.record("a", 40)).isEmpty();
		assertThat(monitor.record("b", 1)).isEmpty();
	}

	@Test
	void aZeroPreviousCountCannotWarn() {
		PageCountMonitor monitor = new PageCountMonitor();
		monitor.record("u", 0);
		assertThat(monitor.record("u", 0)).isEmpty();
	}
}
