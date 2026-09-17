package com.stocker.pricing.ingest.parse;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Parses product cards from New World / PAK'nSave (shared Foodstuffs platform) category pages. */
public final class FoodstuffsProductCardExtractor {

	private static final Logger log = LoggerFactory.getLogger(FoodstuffsProductCardExtractor.class);

	// Fallback for "$4.50" / "$ 12.99" style text; the primary source (meta[itemprop=price]'s
	// content attribute) is already a clean decimal like "3.73" and needs no regex at all.
	private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:\\.[0-9]{1,2})?)");

	private FoodstuffsProductCardExtractor() {
	}

	public static Optional<RawProduct> parse(ProductCardFields fields, ChainId chainId, String storeId) {
		if (fields.rawName() == null || fields.rawName().isBlank()) {
			log.warn("Skipping Foodstuffs product card with no name (code={})", fields.nativeProductCode());
			return Optional.empty();
		}
		if (fields.nativeProductCode() == null || fields.nativeProductCode().isBlank()) {
			log.warn("Skipping Foodstuffs product card with no native product code (name={})", fields.rawName());
			return Optional.empty();
		}

		BigDecimal price = parsePrice(fields.rawPriceText());
		if (price == null) {
			log.warn("Skipping Foodstuffs product card with unparsable price (code={}, rawPriceText={})",
					fields.nativeProductCode(), fields.rawPriceText());
			return Optional.empty();
		}

		return Optional.of(RawProduct.builder()
				.name(fields.rawName().trim())
				.brand(blankToNull(fields.rawBrand()))
				.price(price)
				.currency("NZD")
				.storeUrl(chainId.name().toLowerCase() + ":" + storeId)
				.imageUrl(blankToNull(fields.imageUrl()))
				.source(chainId.name().toLowerCase())
				.chainId(chainId.name().toLowerCase())
				.category(blankToNull(fields.category()))
				.nativeProductCode(fields.nativeProductCode().trim())
				.rawResponse(Map.of())
				.fetchedAt(OffsetDateTime.now())
				.build());
	}

	static BigDecimal parsePrice(String rawPriceText) {
		if (rawPriceText == null || rawPriceText.isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(rawPriceText.trim());
		} catch (NumberFormatException e) {
			// Not a clean decimal - fall back to extracting a "$X.XX" style substring.
		}
		Matcher matcher = PRICE_PATTERN.matcher(rawPriceText);
		if (!matcher.find()) {
			return null;
		}
		try {
			return new BigDecimal(matcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
