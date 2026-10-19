package com.stocker.pricing.normalize;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the pack size inside free text ("Anchor Milk 2L", "Coke 12 x 330ml", "Eggs size 7 30pack").
 * A weight or volume wins over a bare item count when both are present.
 *
 * <p>Item counts understood: "12 pack", "12pk", "12-pack", "6 pk", "30pcs", "x12", "dozen" (12),
 * "half dozen" (6), "2 dozen" (24) and the leading count of "12 x 330ml".
 */
public final class PackSizeParser {

	private static final String MEASURE = "(kg|g|ml|litres?|liters?|ltr|lt|l)";
	private static final Pattern MULTI = Pattern.compile("(\\d+)\\s*[x×]\\s*(\\d+(?:\\.\\d+)?)\\s*" + MEASURE + "\\b");
	private static final Pattern SINGLE = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*" + MEASURE + "\\b");
	private static final Pattern COUNT = Pattern
			.compile("(?<![\\d.])(\\d+)\\s*-?\\s*(?:pk|pack|packs|pcs|pc|pieces|p|ea|ct|count)\\b");
	/** "x12" / "x 12" as a multiplier word, but not "x 330ml" (a measure) and not inside a word. */
	private static final Pattern TIMES = Pattern
			.compile("(?<![a-z0-9])x\\s?(\\d+)\\b(?!\\s*(?:kg|g|ml|l|lt|ltr|litres?|liters?)\\b)");
	private static final Pattern DOZEN = Pattern.compile("(?<![a-z0-9])(?:(\\d+)\\s*|(half)\\s+|a\\s+)?dozen\\b");

	private PackSizeParser() {
	}

	public static Optional<PackSize> parse(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		Optional<PackSize> measure = parseMeasure(text);
		return measure.isPresent() ? measure : parseCount(text);
	}

	/** Weight or volume only ("2L", "500g", "12 x 330ml" = 3960ml). */
	public static Optional<PackSize> parseMeasure(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String lower = text.toLowerCase(Locale.ROOT);
		Matcher multi = MULTI.matcher(lower);
		if (multi.find()) {
			return Optional.of(measure(new BigDecimal(multi.group(2)).multiply(new BigDecimal(multi.group(1))), multi.group(3)));
		}
		Matcher single = SINGLE.matcher(lower);
		if (single.find()) {
			return Optional.of(measure(new BigDecimal(single.group(1)), single.group(2)));
		}
		return Optional.empty();
	}

	/** Item count only ("12 pack", "x12", "dozen"), independent of any weight in the same text. */
	public static Optional<PackSize> parseCount(String text) {
		if (text == null || text.isBlank()) {
			return Optional.empty();
		}
		String lower = text.toLowerCase(Locale.ROOT);
		Matcher count = COUNT.matcher(lower);
		if (count.find()) {
			return Optional.of(each(new BigDecimal(count.group(1))));
		}
		Matcher times = TIMES.matcher(lower);
		if (times.find()) {
			return Optional.of(each(new BigDecimal(times.group(1))));
		}
		Matcher dozen = DOZEN.matcher(lower);
		if (dozen.find()) {
			BigDecimal twelve = BigDecimal.valueOf(12);
			if (dozen.group(1) != null) {
				return Optional.of(each(twelve.multiply(new BigDecimal(dozen.group(1)))));
			}
			return Optional.of(each(dozen.group(2) != null ? BigDecimal.valueOf(6) : twelve));
		}
		Matcher multi = MULTI.matcher(lower);
		if (multi.find()) {
			return Optional.of(each(new BigDecimal(multi.group(1))));
		}
		return Optional.empty();
	}

	/** Text with every size expression removed, so sizes never count as product words. */
	public static String strip(String text) {
		if (text == null) {
			return "";
		}
		String lower = text.toLowerCase(Locale.ROOT);
		lower = MULTI.matcher(lower).replaceAll(" ");
		lower = SINGLE.matcher(lower).replaceAll(" ");
		lower = COUNT.matcher(lower).replaceAll(" ");
		lower = TIMES.matcher(lower).replaceAll(" ");
		return DOZEN.matcher(lower).replaceAll(" ");
	}

	private static PackSize each(BigDecimal value) {
		return new PackSize(PackUnit.EACH, value);
	}

	private static PackSize measure(BigDecimal value, String unit) {
		return switch (unit) {
			case "kg" -> new PackSize(PackUnit.G, value.multiply(BigDecimal.valueOf(1000)));
			case "g" -> new PackSize(PackUnit.G, value);
			case "ml" -> new PackSize(PackUnit.ML, value);
			default -> new PackSize(PackUnit.ML, value.multiply(BigDecimal.valueOf(1000)));
		};
	}
}
