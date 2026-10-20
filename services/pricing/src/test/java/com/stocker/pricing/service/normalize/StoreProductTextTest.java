package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.PriceRecord;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** New World / PAK'nSave put the card subtitle (a size or a pack count) in the brand slot. */
class StoreProductTextTest {

	private static PriceRecord row(String name, String brand) {
		return PriceRecord.builder().rawAttributes(Map.of("name", name, "brand", brand)).build();
	}

	@Test
	void aPackCountInTheBrandSlotJoinsTheName() {
		for (String brand : new String[] {"30pk", "12pk", "12 pack", "30 Pack", "6 pk", "12-pack", "x12", "dozen", "1 dozen"}) {
			PriceRecord r = row("Size 7 Eggs", brand);
			assertThat(StoreProductText.name(r)).as(brand).isEqualTo("Size 7 Eggs " + brand);
			assertThat(StoreProductText.realBrand(r)).as(brand).isNull();
		}
	}

	@Test
	void aCountAlreadyInTheNameIsNotAppendedTwice() {
		assertThat(StoreProductText.name(row("Free Range Eggs Size 7 30pk", "30pk"))).isEqualTo("Free Range Eggs Size 7 30pk");
	}

	@Test
	void unitSizesStillWorkAndRealBrandsStayBrands() {
		assertThat(StoreProductText.name(row("Anchor Milk", "2l"))).isEqualTo("Anchor Milk 2l");
		assertThat(StoreProductText.realBrand(row("Milk", "Anchor"))).isEqualTo("Anchor");
		assertThat(StoreProductText.name(row("Milk", "Anchor"))).isEqualTo("Milk");
	}

	@Test
	void aMeasureWithACountIsAlsoASize() {
		assertThat(StoreProductText.name(row("Coke", "12 x 330ml"))).isEqualTo("Coke 12 x 330ml");
		assertThat(StoreProductText.realBrand(row("Coke", "12 x 330ml"))).isNull();
	}
}
