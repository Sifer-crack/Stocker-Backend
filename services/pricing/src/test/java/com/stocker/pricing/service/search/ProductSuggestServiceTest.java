package com.stocker.pricing.service.search;

import static com.stocker.pricing.service.search.SearchFixtures.CATALOG;
import static com.stocker.pricing.service.search.SearchFixtures.NOW;
import static com.stocker.pricing.service.search.SearchFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.repository.NormalizedProductRepository;
import com.stocker.pricing.service.cache.PricingCacheProperties;
import com.stocker.pricing.service.normalize.NormalizedProductSnapshot;
import com.stocker.pricing.service.search.ProductSuggestService.Suggestion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Search-bar suggestions built from what the chains actually stock (ranking, dedupe, prefix on the last word). */
class ProductSuggestServiceTest {

	private static final List<String> ALL = List.of("newworld", "paknsave", "woolworths");

	private static List<NormalizedProduct> threeChains(String name) {
		return List.of(row("newworld", name, null, "4.00"), row("paknsave", name, null, "3.90"), row("woolworths", name, null, "4.10"));
	}

	private static ProductSuggestService serviceOver(List<NormalizedProduct> rows) {
		NormalizedProductRepository repository = mock(NormalizedProductRepository.class);
		when(repository.findByCapturedAtAfter(any())).thenAnswer(inv -> rows.stream()
				.filter(r -> !r.getCapturedAt().isBefore(inv.getArgument(0))).toList());
		PricingCacheProperties properties = new PricingCacheProperties();
		properties.getSnapshot().setTtl(Duration.ZERO);   // straight through to the faked table
		return new ProductSuggestService(new NormalizedProductSnapshot(repository, properties), CATALOG, properties);
	}

	private static List<NormalizedProduct> shop() {
		List<NormalizedProduct> rows = new ArrayList<>();
		rows.addAll(threeChains("Pams Free Range Eggs Size 7 12 Pack"));
		rows.addAll(threeChains("Sunny Barn Eggs Size 6 12 Pack"));
		rows.add(row("newworld", "Value Barn Eggs 12 Pack", null, "5.00"));
		rows.add(row("newworld", "Egg Fried Rice 300g", null, "4.00"));   // a dish: not eggs
		rows.addAll(threeChains("Anchor Blue Top Milk 2l"));
		rows.addAll(threeChains("Oyster Bay Red Wine 750ml"));
		rows.add(row("woolworths", "Church Road White Wine 750ml", null, "15.00"));
		rows.add(row("newworld", "Church Road White Wine 750ml", null, "15.50"));
		rows.add(row("newworld", "Double Pagoda Shao Xing Cooking Wine 640ml", null, "5.00"));
		rows.add(row("paknsave", "Double Pagoda Shao Xing Cooking Wine 640ml", null, "4.90"));
		rows.add(row("woolworths", "Shiwan Cooking Rice Wine 560ml", null, "4.70"));
		rows.add(row("newworld", "Gao Yue Long Shan Cooking Wine 600ml", null, "6.00"));
		rows.addAll(threeChains("Zed Quinoa Flakes 500g"));
		rows.addAll(threeChains("Zed Quinoa Flakes 1kg"));
		rows.add(row("newworld", "Zed Quinoa Puffs 100g", null, "3.00"));
		rows.add(row("newworld", "Zed Quinoa Pops Crunchy Bites 100g", null, "3.00"));
		rows.add(row("paknsave", "Zed Quinoa Pops Crunchy Bites 100g", null, "3.00"));
		return rows;
	}

	private static List<String> texts(List<Suggestion> list) {
		return list.stream().map(Suggestion::text).toList();
	}

	@Test
	void anEmptyOrOneLetterQueryOffersTheMostWidelyStockedCommonProducts() {
		ProductSuggestService service = serviceOver(shop());

		for (String q : new String[] { "", "  ", "e", null }) {
			List<Suggestion> out = service.suggest(q, 8);
			assertThat(out).isNotEmpty();
			assertThat(out).allSatisfy(s -> {
				assertThat(s.canonicalKey()).isNotNull();
				assertThat(s.chainCount()).isEqualTo(3);
			});
			assertThat(texts(out)).contains("Eggs", "Milk");
		}
		assertThat(service.suggest("", 1)).hasSize(1);
	}

	@Test
	void typingAPrefixSuggestsTheKeyLevelProductOnceNotEveryEggRow() {
		List<Suggestion> out = serviceOver(shop()).suggest("eg", 8);

		Suggestion first = out.get(0);
		assertThat(first.text()).isEqualTo("Eggs");
		assertThat(first.canonicalKey()).isEqualTo("eggs");
		assertThat(first.chainCount()).isEqualTo(3);
		assertThat(first.chains()).containsExactlyElementsOf(ALL);
		assertThat(first.productCount()).isEqualTo(3);   // three distinct egg products (two at 3 chains, one at 1)
		// the many-rows-of-eggs case: one "Eggs", at most a couple of alias-level generics ("Barn eggs"), never a row per product
		assertThat(out.stream().filter(s -> s.text().equals("Eggs"))).hasSize(1);
		assertThat(out.stream().filter(s -> "eggs".equals(s.canonicalKey())).count()).isLessThanOrEqualTo(3);
		assertThat(texts(out)).doesNotContain("Pams Free Range Eggs", "Sunny Barn Eggs");
	}

	@Test
	void theDishIsNotOfferedAsEggsButStillFoundAsASpecificProduct() {
		List<Suggestion> out = serviceOver(shop()).suggest("egg", 8);

		assertThat(out.get(0).text()).isEqualTo("Eggs");
		assertThat(out.get(0).chains()).containsExactlyElementsOf(ALL);
		assertThat(out).anySatisfy(s -> {
			assertThat(s.text()).isEqualTo("Egg Fried Rice");
			assertThat(s.canonicalKey()).isNull();
			assertThat(s.chains()).containsExactly("newworld");
			assertThat(s.sampleSize()).isEqualTo("300g");
		});
	}

	@Test
	void wineOffersTheGenericWinesFirstThenSpecificProducts() {
		List<Suggestion> out = serviceOver(shop()).suggest("wine", 8);

		assertThat(texts(out)).contains("Cooking wine", "Red wine", "White wine");
		assertThat(out.get(0).chainCount()).isEqualTo(3);
		Suggestion red = out.stream().filter(s -> s.text().equals("Red wine")).findFirst().orElseThrow();
		assertThat(red.canonicalKey()).isEqualTo("wine");
		assertThat(red.chains()).containsExactlyElementsOf(ALL);
		Suggestion white = out.stream().filter(s -> s.text().equals("White wine")).findFirst().orElseThrow();
		assertThat(white.chains()).containsExactly("newworld", "woolworths");
		// the keyed niche cooking-wine rows are folded into the "Cooking wine" suggestion, not listed one by one
		assertThat(texts(out)).doesNotContain("Gao Yue Long Shan Cooking Wine");
		assertThat(texts(out)).doesNotHaveDuplicates();
		assertThat(out.stream().filter(s -> "cooking-wine".equals(s.canonicalKey())).count()).isLessThanOrEqualTo(3);
	}

	@Test
	void everyTokenBeforeTheLastMustMatchAWholeWordAndTheLastIsAPrefix() {
		ProductSuggestService service = serviceOver(shop());

		assertThat(service.suggest("cooking wi", 8).get(0).text()).isEqualTo("Cooking wine");
		assertThat(service.suggest("red wine", 8).get(0).text()).isEqualTo("Red wine");
		assertThat(service.suggest("coo wine", 8)).isEmpty();   // "coo" is not a finished word
		assertThat(service.suggest("zzzz", 8)).isEmpty();
	}

	@Test
	void aBrandNarrowsToTheSpecificProductAndKeepsItsKey() {
		List<Suggestion> out = serviceOver(shop()).suggest("pams eg", 8);

		assertThat(out).hasSize(1);
		Suggestion s = out.get(0);
		assertThat(s.text()).isEqualTo("Pams Free Range Eggs");   // no size, no grade, no pack count in the text
		assertThat(s.canonicalKey()).isEqualTo("eggs");
		assertThat(s.sampleSize()).isEqualTo("12 pack");
		assertThat(s.chainCount()).isEqualTo(3);
	}

	@Test
	void rankedByChainCountThenProductCountThenShorterText() {
		List<Suggestion> out = serviceOver(shop()).suggest("zed", 8);

		assertThat(texts(out)).containsExactly("Zed Quinoa Flakes", "Zed Quinoa Pops Crunchy Bites", "Zed Quinoa Puffs");
		assertThat(out.get(0).chainCount()).isEqualTo(3);
		assertThat(out.get(0).productCount()).isEqualTo(2);   // 500g and 1kg are two products
		assertThat(out.get(1).chainCount()).isEqualTo(2);
		assertThat(out.get(2).chainCount()).isEqualTo(1);
	}

	@Test
	void sameChainCountAndProductCountPrefersTheShorterText() {
		List<NormalizedProduct> rows = new ArrayList<>();
		rows.add(row("newworld", "Kiri Tomato Relish Chunky 200g", null, "3.00"));
		rows.add(row("newworld", "Kiri Tomato Relish 200g", null, "3.00"));

		assertThat(texts(serviceOver(rows).suggest("kiri", 8))).containsExactly("Kiri Tomato Relish", "Kiri Tomato Relish Chunky");
	}

	@Test
	void theLimitIsHonouredAndTheDefaultIsEight() {
		List<NormalizedProduct> rows = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			rows.add(row("newworld", "Brand" + i + " Gizmo Item 100g", null, "1.00"));
		}
		ProductSuggestService service = serviceOver(rows);

		assertThat(service.suggest("brand", 3)).hasSize(3);
		assertThat(service.suggest("brand", 0)).hasSize(8);
		assertThat(service.suggest("brand", -5)).hasSize(8);
		assertThat(service.suggest("brand", 500)).hasSizeLessThanOrEqualTo(20);
	}

	@Test
	void rowsOlderThanTheFreshnessWindowOrWithoutAPriceAreIgnored() {
		List<NormalizedProduct> rows = new ArrayList<>();
		rows.add(row("newworld", "Ancient Relic Biscuits 200g", null, "3.00", false, NOW.minusDays(400)));
		rows.add(row("newworld", "Freebie Sample Biscuits 200g", null, "0.00"));

		assertThat(serviceOver(rows).suggest("ancient", 8)).isEmpty();
		assertThat(serviceOver(rows).suggest("freebie", 8)).isEmpty();
	}

	@Test
	void chainIdsAreLowerCaseAndAChainIsCountedOnce() {
		List<NormalizedProduct> rows = new ArrayList<>();
		rows.add(row("NEWWORLD", "Solo Thing 100g", null, "2.00"));
		rows.add(row("NEWWORLD", "Solo Thing 100g", null, "2.10"));   // an older observation of the same listing

		Suggestion s = serviceOver(rows).suggest("solo", 8).get(0);
		assertThat(s.chains()).containsExactly("newworld");
		assertThat(s.chainCount()).isEqualTo(1);
		assertThat(s.productCount()).isEqualTo(1);
	}
}
