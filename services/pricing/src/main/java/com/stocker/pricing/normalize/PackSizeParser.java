package com.stocker.pricing.normalize;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the pack size inside free text ("Anchor Milk 2L", "Coke 12 x 330ml", "Eggs size 7 30pack").
 * A weight or volume wins over a bare item count when both are present.
 */
public final class PackSizeParser {

	private static final String MEASURE = "(kg|g|ml|litres?|liters?|ltr|lt|l)";
	private static final Pattern MULTI = Pattern.compile("(\\d+)\\s*[x×]\\s*(\\d+(?:\\.\\d+)?)\\s*" + MEASURE + "\\b");
	private static final Pattern SINGLE = Pattern.compile("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*" + MEASURE + "\\b");
	private static final Pattern COUNT = Pattern
			.compile("(?<![\\d.])(\\d+)\\s*(?:pk|pack|packs|pcs|pc|pieces|p|ea|ct|count)\\b");

	private PackSizeParser() {
	}

	public static Optional<PackSize> parse(String text) {
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
		Matcher count = COUNT.matcher(lower);
		if (count.find()) {
			return Optional.of(new PackSize(PackUnit.EACH, new BigDecimal(count.group(1))));
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
		return COUNT.matcher(lower).replaceAll(" ");
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
