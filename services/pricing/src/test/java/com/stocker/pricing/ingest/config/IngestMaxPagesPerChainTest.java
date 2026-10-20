package com.stocker.pricing.ingest.config;

import com.stocker.pricing.ingest.ChainId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestMaxPagesPerChainTest {

	@Test
	void aChainWithoutItsOwnCapUsesTheSharedOne() {
		IngestProperties p = new IngestProperties();
		p.setMaxPagesPerCategory(8);

		assertThat(p.maxPagesPerCategory(ChainId.NEWWORLD)).isEqualTo(8);
		assertThat(p.maxPagesPerCategory(ChainId.WOOLWORTHS)).isEqualTo(8);
	}

	@Test
	void aChainCanRaiseItsOwnCapWithoutTouchingTheOthers() {
		IngestProperties p = new IngestProperties();
		p.setMaxPagesPerCategory(8);
		p.getWoolworths().setMaxPagesPerCategory(10);

		assertThat(p.maxPagesPerCategory(ChainId.WOOLWORTHS)).isEqualTo(10);
		assertThat(p.maxPagesPerCategory(ChainId.PAKNSAVE)).isEqualTo(8);
	}

	@Test
	void zeroMeansUseTheSharedCap() {
		IngestProperties p = new IngestProperties();
		p.getWoolworths().setMaxPagesPerCategory(0);

		assertThat(p.maxPagesPerCategory(ChainId.WOOLWORTHS)).isEqualTo(8);
	}
}
