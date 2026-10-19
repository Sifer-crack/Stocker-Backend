package com.stocker.pricing.service.search;

import com.stocker.pricing.model.NormalizedProduct;
import com.stocker.pricing.normalize.PackSize;
import com.stocker.pricing.normalize.PackSizeParser;
import com.stocker.pricing.normalize.PackUnit;
import com.stocker.pricing.normalize.ProductNormalizer;
import com.stocker.pricing.normalize.QueryParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.util.StringUtils;

/**
 * What makes two chain listings the SAME product, and the stable {@code productKey} that names it.
 *
 * <p><b>Product key</b> = {@code <name>|<grade>|<pack>}, segments joined with {@code |}, the empty ones left out:
 * <ul>
 * <li><b>name</b>: the brand words followed by the name's words, lower case, joined with {@code -}; size, pack count,
 * egg grade and noise words ("fresh", "pack", "nz") never appear ({@code anchor-blue-top-milk});</li>
 * <li><b>grade</b>: {@code size-7} when the name states an NZ egg grade;</li>
 * <li><b>pack</b>: the pack size in base units, {@code <quantity><unit>}: {@code 2000ml}, {@code 500g},
 * {@code 12each}. Taken from the stored pack size, else parsed from the name.</li>
 * </ul>
 *
 * <p><b>Identity</b> (what listings are merged on) is the same three parts, but the name part is the SET of
 * normalised words ({@link ProductNormalizer#wordList}: singular, spelling variants collapsed, noise dropped),
 * sorted, with the brand words included. So word order ("Blue Top Anchor Milk"), plurals ("egg"/"eggs"), noise
 * ("Fresh"), and the way a size is written (2L, 2l, 2 litre, 2000ml) do not matter, and a chain that leaves the brand
 * out of the brand slot but puts it in the name merges with one that fills the slot. A different brand, size, count
 * or grade is a different product. {@link #canonicalOfKey} maps any key (also one in another word order) to its
 * identity, so a key stays valid even when the representative listing changes between two searches.
 */
public final class ProductIdentity {

	private ProductIdentity() {
	}

	/** The identity listings are grouped by. */
	public static String canonical(NormalizedProduct row) {
		Set<String> words = new TreeSet<>(ProductNormalizer.wordList(brandWords(row) + " " + row.getDisplayName()));
		return identity(words, gradeSegment(row), packSegment(row));
	}

	/** The identity of a product key as handed out by {@link #displayKey}, in any word order. */
	public static String canonicalOfKey(String productKey) {
		String[] segments = productKey == null ? new String[] { "" } : productKey.trim().toLowerCase(Locale.ROOT).split("\\|", -1);
		Set<String> words = new TreeSet<>(ProductNormalizer.wordList(segments[0].replace('-', ' ')));
		StringBuilder rest = new StringBuilder();
		for (int i = 1; i < segments.length; i++) {
			rest.append('|').append(segments[i].trim());
		}
		return String.join("-", words) + rest;
	}

	/** The key handed to clients for one listing of the product (brand first, then the name's own word order). */
	public static String displayKey(NormalizedProduct row) {
		Set<String> tokens = new LinkedHashSet<>();
		tokens.addAll(rawTokens(brandWords(row)));
		tokens.addAll(rawTokens(row.getDisplayName()));
		StringBuilder key = new StringBuilder(String.join("-", tokens));
		appendSegment(key, gradeSegment(row));
		appendSegment(key, packSegment(row));
		return key.toString();
	}

	/** The brand, unless the scraper put a pack size in the brand slot (then there is none). */
	public static String brandOf(NormalizedProduct row) {
		String brand = row.getBrand();
		return StringUtils.hasText(brand) && PackSizeParser.parse(brand).isEmpty() ? brand.trim() : null;
	}

	/** The stored pack size, else the one the name (or a size-like brand slot) states, else null. */
	public static PackSize packOf(NormalizedProduct row) {
		PackUnit unit = row.getPackUnit() == null ? null : PackUnit.fromCode(row.getPackUnit());
		if (unit != null && row.getPackQuantity() != null && row.getPackQuantity().signum() > 0) {
			return new PackSize(unit, row.getPackQuantity());
		}
		PackSize fromName = PackSizeParser.parse(row.getDisplayName()).orElse(null);
		if (fromName != null) {
			return fromName;
		}
		return row.getBrand() == null ? null : PackSizeParser.parse(row.getBrand()).orElse(null);
	}

	public static String gradeOf(NormalizedProduct row) {
		Integer grade = QueryParser.gradeOf(row.getDisplayName());
		return grade == null ? null : grade.toString();
	}

	private static String identity(Set<String> words, String grade, String pack) {
		StringBuilder id = new StringBuilder(String.join("-", words));
		appendSegment(id, grade);
		appendSegment(id, pack);
		return id.toString();
	}

	private static void appendSegment(StringBuilder key, String segment) {
		if (segment != null) {
			key.append('|').append(segment);
		}
	}

	private static String brandWords(NormalizedProduct row) {
		String brand = brandOf(row);
		return brand == null ? "" : brand;
	}

	private static String gradeSegment(NormalizedProduct row) {
		String grade = gradeOf(row);
		return grade == null ? null : "size-" + grade;
	}

	private static String packSegment(NormalizedProduct row) {
		PackSize pack = packOf(row);
		return pack == null ? null : pack.quantity().stripTrailingZeros().toPlainString() + pack.unit().code();
	}

	/** Lower-case words of the text in their own spelling and order, without sizes, grades and noise. */
	private static List<String> rawTokens(String text) {
		List<String> tokens = new ArrayList<>();
		if (!StringUtils.hasText(text)) {
			return tokens;
		}
		String cleaned = QueryParser.stripGrade(PackSizeParser.strip(text)).replace("'", "").replace("’", "");
		for (String token : cleaned.split("[^a-z0-9]+")) {
			if (!token.isEmpty() && !ProductNormalizer.wordList(token).isEmpty()) {
				tokens.add(token);
			}
		}
		return tokens;
	}
}
