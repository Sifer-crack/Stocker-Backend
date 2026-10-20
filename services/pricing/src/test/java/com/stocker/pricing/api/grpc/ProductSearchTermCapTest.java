package com.stocker.pricing.api.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductSearchTermCapTest {

	@Test
	void aShortTermIsOnlyTrimmed() {
		assertThat(ProductSearchGrpcController.capTerm("  full cream milk ")).isEqualTo("full cream milk");
	}

	@Test
	void aTermIsCappedAt100Characters() {
		String capped = ProductSearchGrpcController.capTerm("a".repeat(5000));

		assertThat(capped).hasSize(100);
	}

	@Test
	void aTermOfExactly100CharactersIsKept() {
		assertThat(ProductSearchGrpcController.capTerm("b".repeat(100))).hasSize(100);
	}

	@Test
	void nullBecomesEmpty() {
		assertThat(ProductSearchGrpcController.capTerm(null)).isEmpty();
	}
}
