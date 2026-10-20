package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.normalize.CommonProductCatalog;
import com.stocker.pricing.normalize.QueryParser;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.repository.UnmatchedSearchTermRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pack counts ("30pk") scraped into the brand slot become pack_quantity + unit prices, for new and already-stored rows. */
class NormalizedProductPackCountTest {

	private NormalizedProductRepository repository;
	private NormalizedProductService service;

	@BeforeEach
	void setUp() {
		repository = mock(NormalizedProductRepository.class);
		service = new NormalizedProductService(repository, mock(UnmatchedSearchTermRepository.class),
				CommonProductCatalog.loadDefault());
	}

	private static PriceRecord record(String chain, String name, String brand, String price) {
		return PriceRecord.builder().itemId(chain + ":1").storeId("s1").chainId(chain)
				.priceAmount(new BigDecimal(price)).currency("NZD").capturedAt(OffsetDateTime.now())
				.rawAttributes(Map.of("name", name, "brand", brand)).build();
	}

	@Test
	void observeStoresThePackCountFromTheBrandSlotForBothFoodstuffsChains() {
		for (String chain : new String[] {"paknsave", "newworld"}) {
			when(repository.findByItemIdAndStoreId(any(), any())).thenReturn(Optional.empty());

			service.observe(record(chain, "Size 7 Eggs", "30pk", "15.00"));

			ArgumentCaptor<NormalizedProduct> saved = ArgumentCaptor.forClass(NormalizedProduct.class);
			verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
			NormalizedProduct row = saved.getValue();
			assertThat(row.getCanonicalKey()).as(chain).isEqualTo("eggs");
			assertThat(row.getPackUnit()).as(chain).isEqualTo("each");
			assertThat(row.getPackQuantity()).as(chain).isEqualByComparingTo("30");
			assertThat(row.getUnitPrice()).as(chain).isEqualByComparingTo("0.50");
			assertThat(row.getBrand()).as(chain).isNull();
			assertThat(row.getDisplayName()).as(chain).isEqualTo("Size 7 Eggs 30pk");
			org.mockito.Mockito.clearInvocations(repository);
		}
	}

	@Test
	void theQueryEggsSize7ThirtyPackMatchesTheStoredNameAndNotTheOtherPackSizes() {
		var query = QueryParser.parse("eggs size 7 30 pack");
		assertThat(query.satisfiedBy(QueryParser.parse("Size 7 Eggs 30pk"), null)).isTrue();
		assertThat(query.satisfiedBy(QueryParser.parse("Size 7 Eggs 12pk"), null)).isFalse();
		assertThat(query.satisfiedBy(QueryParser.parse("Size 6 Eggs 30pk"), null)).isFalse();
		assertThat(query.satisfiedBy(QueryParser.parse("Free Range Eggs Size 7 30 Pack"), null)).isTrue();
		assertThat(QueryParser.parse("eggs size 7 tray 30").satisfiedBy(QueryParser.parse("Size 7 Eggs 30pk"), null)).isTrue();
	}

	private NormalizedProduct storedRow(String display, String brand) {
		return NormalizedProduct.builder().id(UUID.randomUUID()).itemId("paknsave:77").storeId("s1").chainId("paknsave")
				.canonicalKey("eggs").normalizedName("egg").displayName(display).brand(brand)
				.priceAmount(new BigDecimal("15.00")).currency("NZD").capturedAt(OffsetDateTime.now())
				.updatedAt(OffsetDateTime.now().minusDays(2)).build();
	}

	private void pages(List<NormalizedProduct> all) {
		when(repository.findAll(any(Pageable.class))).thenAnswer(inv -> {
			Pageable p = inv.getArgument(0);
			int from = (int) p.getOffset();
			int to = Math.min(all.size(), from + p.getPageSize());
			return new PageImpl<>(from >= all.size() ? List.of() : new ArrayList<>(all.subList(from, to)), p, all.size());
		});
	}

	@Test
	void rekeyRecoversThePackCountOfAnAlreadyStoredRowFromItsBrandAndIsIdempotent() {
		NormalizedProduct stored = storedRow("Size 7 Eggs", "30pk");
		pages(List.of(stored));

		var first = service.rekeyAll(10);

		assertThat(first.changed()).isEqualTo(1);
		assertThat(stored.getPackUnit()).isEqualTo("each");
		assertThat(stored.getPackQuantity()).isEqualByComparingTo("30");
		assertThat(stored.getUnitPrice()).isEqualByComparingTo("0.50");
		assertThat(stored.getBrand()).isNull();
		assertThat(stored.getDisplayName()).isEqualTo("Size 7 Eggs 30pk");
		org.mockito.Mockito.clearInvocations(repository);

		var second = service.rekeyAll(10);

		assertThat(second.changed()).isZero();
		verify(repository, never()).saveAll(any());
		assertThat(stored.getPackQuantity()).isEqualByComparingTo("30");
	}

	@Test
	void rekeyLeavesARealBrandAlone() {
		NormalizedProduct stored = storedRow("Free Range Eggs Size 7 12 Pack", "Pams");
		pages(List.of(stored));

		service.rekeyAll(10);

		assertThat(stored.getBrand()).isEqualTo("Pams");
		assertThat(stored.getPackQuantity()).isEqualByComparingTo("12");
	}
}
