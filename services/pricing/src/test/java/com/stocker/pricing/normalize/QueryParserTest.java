package com.stocker.pricing.normalize;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryParserTest {

	private static PackSize ml(String v) {
		return new PackSize(PackUnit.ML, new BigDecimal(v));
	}

	private static PackSize g(String v) {
		return new PackSize(PackUnit.G, new BigDecimal(v));
	}

	private static PackSize each(String v) {
		return new PackSize(PackUnit.EACH, new BigDecimal(v));
	}

	@Test
	void unitSizesAreExtractedAndRemovedFromTheWords() {
		assertThat(QueryParser.parse("milk 2L").measure().sameAs(ml("2000"))).isTrue();
		assertThat(QueryParser.parse("milk 1.5 litres").measure().sameAs(ml("1500"))).isTrue();
		assertThat(QueryParser.parse("flour 500g").measure().sameAs(g("500"))).isTrue();
		assertThat(QueryParser.parse("flour 1kg").measure().sameAs(g("1000"))).isTrue();
		assertThat(QueryParser.parse("coke 330ml").measure().sameAs(ml("330"))).isTrue();
		assertThat(QueryParser.parse("milk 2L").words()).containsExactly("milk");
		assertThat(QueryParser.parse("1.5 litres full cream milk").words()).containsExactly("fullcream", "milk");
	}

	@Test
	void packCountsAreExtractedInEveryWriting() {
		for (String text : new String[] {"eggs 12 pack", "eggs 12pk", "eggs 12-pack", "eggs x12", "eggs x 12", "eggs dozen",
				"eggs 1 dozen", "12 pack eggs"}) {
			ParsedQuery q = QueryParser.parse(text);
			assertThat(q.count()).as(text).isNotNull();
			assertThat(q.count().sameAs(each("12"))).as(text).isTrue();
			assertThat(q.words()).as(text).containsExactly("egg");
			assertThat(q.measure()).as(text).isNull();
		}
		assertThat(QueryParser.parse("eggs half dozen").count().sameAs(each("6"))).isTrue();
		assertThat(QueryParser.parse("eggs 6 pk").count().sameAs(each("6"))).isTrue();
		assertThat(QueryParser.parse("eggs 2 dozen").count().sameAs(each("24"))).isTrue();
	}

	@Test
	void aMultipackKeepsBothTheTotalMeasureAndTheCount() {
		ParsedQuery q = QueryParser.parse("coke 12 x 330ml");
		assertThat(q.measure().sameAs(ml("3960"))).isTrue();
		assertThat(q.count().sameAs(each("12"))).isTrue();
		assertThat(q.words()).containsExactly("coke");
	}

	@Test
	void eggGradesAreExtractedAndRemoved() {
		for (String text : new String[] {"eggs size 7", "size 7 eggs", "eggs size7", "eggs s7"}) {
			ParsedQuery q = QueryParser.parse(text);
			assertThat(q.eggGrade()).as(text).isEqualTo(7);
			assertThat(q.words()).as(text).containsExactly("egg");
		}
		assertThat(QueryParser.parse("eggs size 6 12 pack").eggGrade()).isEqualTo(6);
		assertThat(QueryParser.parse("eggs").eggGrade()).isNull();
		assertThat(QueryParser.parse("size medium shirt").eggGrade()).isNull();
	}

	@Test
	void aPlainTermHasNoQualifier() {
		ParsedQuery q = QueryParser.parse("tim tams");
		assertThat(q.hasQualifier()).isFalse();
		assertThat(q.words()).containsExactly("timtam");
	}

	@Test
	void aProductMustHaveEveryQualifierTheQueryStates() {
		ParsedQuery q = QueryParser.parse("eggs size 7 12 pack");
		assertThat(q.satisfiedBy(QueryParser.parse("Pams Free Range Eggs Size 7 12 Pack"), null)).isTrue();
		assertThat(q.satisfiedBy(QueryParser.parse("Pams Free Range Eggs Size 6 12 Pack"), null)).isFalse();
		assertThat(q.satisfiedBy(QueryParser.parse("Pams Free Range Eggs Size 7 6 Pack"), null)).isFalse();
		assertThat(q.satisfiedBy(QueryParser.parse("Pams Free Range Eggs"), null)).isFalse();
		assertThat(QueryParser.parse("milk 2L").satisfiedBy(QueryParser.parse("Fresh Milk"), ml("2000"))).isTrue();
		assertThat(QueryParser.parse("milk 2L").satisfiedBy(QueryParser.parse("Fresh Milk 1L"), ml("1000"))).isFalse();
	}
}
