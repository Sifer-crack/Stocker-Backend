package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.ChainProductService.ChainProduct;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** How a user browses a favorite store: a store with a realistic stored catalogue, narrowed by a search. */
@Tag("service")
class ChainProductScenarioTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);

	private ChainProductService service;

	private static PriceRecord row(String chain, String code, String name, String size, String price) {
		return PriceRecord.builder().itemId(chain + ":" + code).chainId(chain).storeId(chain + ":").channel("pickup")
				.priceAmount(new BigDecimal(price)).currency("NZD").capturedAt(NOW).createdAt(NOW)
				.rawAttributes(Map.of("name", name, "brand", size)).build();
	}

	@BeforeEach
	void setUp() {
		PriceRecordRepository repository = mock(PriceRecordRepository.class);
		when(repository.findIngestedByChainSince(eq("paknsave"), any())).thenReturn(List.of(
				row("paknsave", "1", "Anchor Blue Top Milk", "2l", "4.80"),
				row("paknsave", "2", "Anchor Blue Top Milk", "1l", "2.60"),
				row("paknsave", "3", "Pams Free Range Eggs 12 Pack", "Pams", "6.10"),
				row("paknsave", "4", "Meadow Fresh Butter", "500g", "7.20")));
		when(repository.findIngestedByChainSince(eq("woolworths"), any())).thenReturn(List.of(
				row("woolworths", "9", "Anchor Blue Top Milk", "2l", "5.10")));
		service = new ChainProductService(repository, new PricingCacheProperties());
	}

	@Test
	void browsingAStoreListsItsWholeCatalogue() {
		assertThat(service.list("paknsave", null, 50)).extracting(ChainProduct::name)
				.containsExactly("Anchor Blue Top Milk 1l", "Anchor Blue Top Milk 2l", "Meadow Fresh Butter 500g",
						"Pams Free Range Eggs 12 Pack");
	}

	@Test
	void searchingWithinAStoreFindsOnlyItsMatches() {
		assertThat(service.list("paknsave", "milk", 50)).extracting(ChainProduct::name)
				.containsExactlyInAnyOrder("Anchor Blue Top Milk 1l", "Anchor Blue Top Milk 2l");
	}

	@Test
	void eachStoreShowsOnlyItsOwnPrices() {
		assertThat(service.list("woolworths", "milk", 50)).singleElement()
				.satisfies(product -> assertThat(product.priceAmount()).isEqualByComparingTo("5.10"));
		assertThat(service.list("paknsave", "milk 2l", 1)).singleElement()
				.satisfies(product -> assertThat(product.priceAmount()).isEqualByComparingTo("4.80"));
	}

	@Test
	void aStoreWithNothingStoredYetIsEmptyAndABadStoreIsRejected() {
		assertThat(service.list("newworld", null, 50)).isEmpty();
		assertThatThrownBy(() -> service.list("countdown", null, 50)).isInstanceOf(IllegalArgumentException.class);
	}
}
