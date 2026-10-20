package com.stocker.pricing.ingest.parse;

import com.stocker.pricing.ingest.ChainId;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChainPageTemplateTest {

	@Test
	void newWorldAndPaknsaveShareTheFoodstuffsSelectorsAndOnlyDifferInChain() {
		ChainPageTemplate nw = ChainPageTemplate.foodstuffs(ChainId.NEWWORLD);
		ChainPageTemplate pak = ChainPageTemplate.foodstuffs(ChainId.PAKNSAVE);

		assertThat(nw.chain()).isEqualTo(ChainId.NEWWORLD);
		assertThat(pak.chain()).isEqualTo(ChainId.PAKNSAVE);
		assertThat(pak.cardSelector()).isEqualTo(nw.cardSelector()).isEqualTo("div[itemtype='https://schema.org/Product']");
		assertThat(pak.fields()).isEqualTo(nw.fields());
		assertThat(nw.fields()).containsKeys(ChainPageTemplate.NAME, ChainPageTemplate.BRAND, ChainPageTemplate.PRICE_META,
				ChainPageTemplate.PRICE_DOLLARS, ChainPageTemplate.PRICE_CENTS, ChainPageTemplate.IMAGE, ChainPageTemplate.CODE);
		assertThat(nw.showingRegex()).isNotNull();
	}

	@Test
	void aChainOverrideChangesOnlyThatChainsCopy() {
		ChainPageTemplate nw = ChainPageTemplate.foodstuffs(ChainId.NEWWORLD);
		ChainPageTemplate pak = ChainPageTemplate.foodstuffs(ChainId.PAKNSAVE)
				.withField(ChainPageTemplate.NAME, ChainPageTemplate.FieldSpec.text("h3.title"));

		assertThat(pak.fields().get(ChainPageTemplate.NAME).selector()).isEqualTo("h3.title");
		assertThat(nw.fields().get(ChainPageTemplate.NAME).selector()).isEqualTo("[data-testid='product-title']");
	}

	@Test
	void foodstuffsTemplateIsNotForWoolworths() {
		assertThatThrownBy(() -> ChainPageTemplate.foodstuffs(ChainId.WOOLWORTHS)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void woolworthsHasItsOwnSelectorsAndTheToCountLine() {
		ChainPageTemplate ww = ChainPageTemplate.woolworths();

		assertThat(ww.chain()).isEqualTo(ChainId.WOOLWORTHS);
		assertThat(ww.cardSelector()).isEqualTo("div[class^='product-tile_imgAndTextArea_']");
		assertThat(ww.fields().get(ChainPageTemplate.PRICE_TEXT).selector()).isEqualTo("span[class^='product-price_value_']");
		assertThat(ww.showingRegex()).isEqualTo(CategoryPagination.WOOLWORTHS_SOURCE);
		assertThat(ww.paginationNavSelector()).isEqualTo("nav[aria-label='Pagination' i]");
		assertThat(ww.pageParam()).isEqualTo("page");
		assertThat(ChainPageTemplate.foodstuffs(ChainId.NEWWORLD).pageParam()).isEqualTo("pg");
	}

	@Test
	void foodstuffsCodeIsTheDigitsOfTheCardTestIdAndNothingElse() {
		ChainPageTemplate nw = ChainPageTemplate.foodstuffs(ChainId.NEWWORLD);

		assertThat(nw.nativeCode("product-5000518-EA-000")).isEqualTo("5000518");
		assertThat(nw.nativeCode("product-5000518")).isNull();
		assertThat(nw.nativeCode("something-else")).isNull();
		assertThat(nw.nativeCode(null)).isNull();
	}

	@Test
	void woolworthsCodeIsTheSecondToLastPathSegmentOfTheProductLink() {
		ChainPageTemplate ww = ChainPageTemplate.woolworths();

		assertThat(ww.nativeCode("/shop/productdetails/131002/anchor-blue-top-milk")).isEqualTo("131002");
		assertThat(ww.nativeCode("/shop/productdetails/131003/anchor-light-milk?x=1")).isEqualTo("131003");
		assertThat(ww.nativeCode("nopath")).isNull();
		assertThat(ww.nativeCode(null)).isNull();
	}

	@Test
	void priceTextPrefersTheSchemaMetaThenDollarsDotCentsThenThePriceText() {
		ChainPageTemplate nw = ChainPageTemplate.foodstuffs(ChainId.NEWWORLD);
		Map<String, String> tile = new HashMap<>();
		tile.put(ChainPageTemplate.PRICE_META, "3.50");
		tile.put(ChainPageTemplate.PRICE_DOLLARS, "9");
		tile.put(ChainPageTemplate.PRICE_CENTS, "99");
		assertThat(nw.priceText(tile)).isEqualTo("3.50");

		tile.put(ChainPageTemplate.PRICE_META, " ");
		assertThat(nw.priceText(tile)).isEqualTo("9.99");

		tile.remove(ChainPageTemplate.PRICE_CENTS);
		assertThat(nw.priceText(tile)).isNull();

		Map<String, String> woolworths = Map.of(ChainPageTemplate.PRICE_TEXT, "$3.49");
		assertThat(ChainPageTemplate.woolworths().priceText(woolworths)).isEqualTo("$3.49");
	}

	@Test
	void theEvaluateArgumentIsPlainMapsListsAndStrings() {
		Map<String, Object> arg = ChainPageTemplate.foodstuffs(ChainId.NEWWORLD).toEvaluateArgument();

		assertThat(arg).containsKeys("card", "fields", "showing", "nav");
		@SuppressWarnings("unchecked")
		Map<String, Object> fields = (Map<String, Object>) arg.get("fields");
		assertThat(fields).containsKeys("name", "code");
		assertThat(fields.get("code")).isEqualTo(Map.of("selector", "", "attribute", "data-testid"));
	}

	@Test
	void priceTextJoinsDollarsAndCentsEvenWhenTheCentsSpanStartsWithItsOwnDot() {
		ChainPageTemplate nw = ChainPageTemplate.foodstuffs(ChainId.PAKNSAVE);
		assertThat(nw.priceText(tile("2", ".99"))).isEqualTo("2.99");
		assertThat(nw.priceText(tile("$2", " .99 "))).isEqualTo("2.99");
		assertThat(nw.priceText(tile("2.", "99"))).isEqualTo("2.99");
		assertThat(nw.priceText(tile(" 12 ", "5"))).isEqualTo("12.5");
		assertThat(nw.priceText(tile("2", "99"))).isEqualTo("2.99");
	}

	private static Map<String, String> tile(String dollars, String cents) {
		Map<String, String> tile = new HashMap<>();
		tile.put(ChainPageTemplate.PRICE_DOLLARS, dollars);
		tile.put(ChainPageTemplate.PRICE_CENTS, cents);
		return tile;
	}
}
