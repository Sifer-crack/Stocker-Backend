package com.stocker.pricing.service;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.repository.PriceRecordRepository;
import com.stocker.pricing.service.ChainProductService.ChainProduct;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainProductServiceTest {

	private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 19, 3, 0, 0, 0, ZoneOffset.UTC);

	private PriceRecordRepository repository;
	private PricingCacheProperties properties;
	private ChainProductService service;

	@BeforeEach
	void setUp() {
		repository = mock(PriceRecordRepository.class);
		properties = new PricingCacheProperties();
		service = new ChainProductService(repository, properties);
	}

	private static PriceRecord row(String itemId, String name, String brand, String price, OffsetDateTime at) {
		Map<String, Object> attributes = new HashMap<>();
		if (name != null) {
			attributes.put("name", name);
		}
		if (brand != null) {
			attributes.put("brand", brand);
		}
		return PriceRecord.builder().itemId(itemId).chainId("paknsave").storeId("paknsave:").channel("pickup")
				.priceAmount(new BigDecimal(price)).currency("NZD").capturedAt(at).createdAt(at)
				.rawAttributes(attributes).build();
	}

	private void stored(PriceRecord... rows) {
		when(repository.findIngestedByChainSince(eq("paknsave"), any())).thenReturn(List.of(rows));
	}

	@Test
	void eachProductIsListedOnceWithItsNewestPrice() {
		stored(row("paknsave:1", "Anchor Blue Milk", "Anchor", "3.90", NOW.minusDays(1)),
				row("paknsave:1", "Anchor Blue Milk", "Anchor", "4.20", NOW));

		List<ChainProduct> products = service.list("paknsave", null, 50);

		assertThat(products).hasSize(1);
		assertThat(products.get(0).priceAmount()).isEqualByComparingTo("4.20");
		assertThat(products.get(0).capturedAt()).isEqualTo(NOW);
		assertThat(products.get(0).itemId()).isEqualTo("paknsave:1");
		assertThat(products.get(0).currency()).isEqualTo("NZD");
	}

	@Test
	void aPackSizeScrapedIntoTheBrandSlotBecomesPartOfTheName() {
		stored(row("paknsave:1", "Anchor Blue Milk", "2l", "3.73", NOW));

		ChainProduct product = service.list("paknsave", null, 50).get(0);

		assertThat(product.name()).isEqualTo("Anchor Blue Milk 2l");
		assertThat(product.packSize()).isEqualTo("2l");
		assertThat(product.brand()).isNull();
	}

	@Test
	void aRealBrandStaysTheBrand() {
		stored(row("paknsave:1", "Blue Milk", "Anchor", "3.73", NOW));

		ChainProduct product = service.list("paknsave", null, 50).get(0);

		assertThat(product.brand()).isEqualTo("Anchor");
		assertThat(product.packSize()).isNull();
		assertThat(product.name()).isEqualTo("Blue Milk");
	}

	@Test
	void withoutAQueryProductsAreSortedByName() {
		stored(row("paknsave:2", "Pams Eggs 12 Pack", null, "5.50", NOW), row("paknsave:1", "Anchor Milk", null, "3.00", NOW));

		assertThat(service.list("paknsave", " ", 50)).extracting(ChainProduct::name)
				.containsExactly("Anchor Milk", "Pams Eggs 12 Pack");
	}

	@Test
	void aQueryKeepsOnlyMatchingProductsBestFirst() {
		stored(row("paknsave:1", "Anchor Blue Top Milk 2L", null, "4.80", NOW),
				row("paknsave:2", "Pams Eggs 12 Pack", null, "5.50", NOW),
				row("paknsave:3", "Milk", null, "2.00", NOW));

		List<ChainProduct> products = service.list("paknsave", "milk", 50);

		assertThat(products).extracting(ChainProduct::name).containsExactly("Milk", "Anchor Blue Top Milk 2L");
	}

	@Test
	void rowsWithoutANameOrAPriceAreSkipped() {
		stored(row("paknsave:1", null, null, "3.00", NOW), row("paknsave:2", "Free Sample", null, "0.00", NOW),
				row("paknsave:3", "Real Product", null, "1.00", NOW));

		assertThat(service.list("paknsave", null, 50)).extracting(ChainProduct::name).containsExactly("Real Product");
	}

	@Test
	void theLimitIsAppliedAndClamped() {
		PriceRecord[] rows = new PriceRecord[300];
		for (int i = 0; i < rows.length; i++) {
			rows[i] = row("paknsave:" + i, "Product %03d".formatted(i), null, "1.00", NOW);
		}
		stored(rows);

		assertThat(service.list("paknsave", null, 5)).hasSize(5);
		assertThat(service.list("paknsave", null, 0)).hasSize(ChainProductService.DEFAULT_LIMIT);
		assertThat(service.list("paknsave", null, -3)).hasSize(ChainProductService.DEFAULT_LIMIT);
		assertThat(service.list("paknsave", null, 10_000)).hasSize(ChainProductService.MAX_LIMIT);
	}

	@Test
	void theChainIsCaseInsensitive() {
		stored();

		service.list(" PAKNSAVE ", null, 10);

		verify(repository).findIngestedByChainSince(eq("paknsave"), any());
	}

	@Test
	void anUnknownChainIsRejectedWithoutTouchingTheDatabase() {
		assertThatThrownBy(() -> service.list("countdown", null, 10)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("countdown");
		assertThatThrownBy(() -> service.list(" ", null, 10)).isInstanceOf(IllegalArgumentException.class);
		verify(repository, never()).findIngestedByChainSince(any(), any());
	}

	@Test
	void onlyRowsNewerThanTheMatchWindowAreRequested() {
		stored();

		service.list("paknsave", null, 10);

		ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
		verify(repository).findIngestedByChainSince(eq("paknsave"), since.capture());
		OffsetDateTime expected = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getMatch().getMaxAge());
		assertThat(since.getValue()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
	}

	@Test
	void nothingStoredIsAnEmptyListNotAnError() {
		stored();

		assertThat(service.list("paknsave", "milk", 10)).isEmpty();
	}
}
