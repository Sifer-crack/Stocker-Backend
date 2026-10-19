package com.stocker.pricing.normalize;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a search term into product words and size qualifiers.
 *
 * <ul>
 * <li>unit sizes: 2L, 1.5 litres, 500g, 1kg, 330ml, "12 x 330ml" (multiplied out);</li>
 * <li>pack counts: 12 pack, 12pk, 12-pack, 6 pk, x12, dozen (12), half dozen (6), 2 dozen (24);</li>
 * <li>NZ egg grades: "size 7", "size6", "s7" (sizes 4 to 9).</li>
 * </ul>
 *
 * The qualifiers never count as words when names are matched. A product name is parsed the same
 * way, so a query and a product can be compared qualifier by qualifier.
 */
public final class QueryParser {

	private static final Pattern GRADE = Pattern.compile("(?<![a-z0-9])(?:size\\s*|s)([4-9])\\b");

	private QueryParser() {
	}

	public static ParsedQuery parse(String text) {
		String original = text == null ? "" : text;
		var wordList = ProductNormalizer.wordList(original);
		return new ParsedQuery(original, new LinkedHashSet<>(wordList), java.util.List.copyOf(wordList),
				PackSizeParser.parseMeasure(original).orElse(null), PackSizeParser.parseCount(original).orElse(null),
				gradeOf(original));
	}

	/** The egg grade named in the text ("size 7", "s7"), or null. */
	public static Integer gradeOf(String text) {
		if (text == null) {
			return null;
		}
		Matcher matcher = GRADE.matcher(text.toLowerCase(Locale.ROOT));
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}

	/** Lower-case text without egg-grade expressions. */
	public static String stripGrade(String text) {
		return text == null ? "" : GRADE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
	}
}
