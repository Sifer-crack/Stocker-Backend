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

/** Parses product cards from Woolworths NZ category pages (native code = stockcode). */
public final class WoolworthsProductCardExtractor {

	private static final Logger log = LoggerFactory.getLogger(WoolworthsProductCardExtractor.class);

	private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:\\.[0-9]{1,2})?)");

	private WoolworthsProductCardExtractor() {
	}

	public static Optional<RawProduct> parse(ProductCardFields fields, String storeId) {
		if (fields.rawName() == null || fields.rawName().isBlank()) {
			log.warn("Skipping Woolworths product card with no name (stockcode={})", fields.nativeProductCode());
			return Optional.empty();
		}
		if (fields.nativeProductCode() == null || fields.nativeProductCode().isBlank()) {
			log.warn("Skipping Woolworths product card with no stockcode (name={})", fields.rawName());
			return Optional.empty();
		}

		BigDecimal price = parsePrice(fields.rawPriceText());
		if (price == null) {
			log.warn("Skipping Woolworths product card with unparsable price (stockcode={}, rawPriceText={})",
					fields.nativeProductCode(), fields.rawPriceText());
			return Optional.empty();
		}

		return Optional.of(RawProduct.builder()
				.name(cleanName(fields.rawName()))
				.brand(blankToNull(fields.rawBrand()))
				.price(price)
				.currency("NZD")
				.storeUrl(ChainId.WOOLWORTHS.name().toLowerCase() + ":" + storeId)
				.imageUrl(blankToNull(fields.imageUrl()))
				.source(ChainId.WOOLWORTHS.name().toLowerCase())
				.chainId(ChainId.WOOLWORTHS.name().toLowerCase())
				.category(blankToNull(fields.category()))
				.nativeProductCode(fields.nativeProductCode().trim())
				.rawResponse(Map.of())
				.fetchedAt(OffsetDateTime.now())
				.build());
	}

	/** Mirrors Jason-nzd/countdown-scraper's title cleanup (min-order notes, brand prefixes). */
	static String cleanName(String rawName) {
		String name = rawName.trim();
		int minOrderIdx = name.indexOf("Min Order");
		if (minOrderIdx >= 0) {
			name = name.substring(0, minOrderIdx).trim();
		}
		int minimumIdx = name.indexOf("(Minimum ");
		if (minimumIdx >= 0) {
			name = name.substring(0, minimumIdx).trim();
		}
		name = name.replaceAll("(?i) per kg", "").trim();
		if (name.startsWith("Woolworths Fresh ")) {
			name = name.substring("Woolworths Fresh ".length()).trim();
		} else if (name.startsWith("Woolworths ")) {
			name = name.substring("Woolworths ".length()).trim();
		}
		return name;
	}

	static BigDecimal parsePrice(String rawPriceText) {
		if (rawPriceText == null || rawPriceText.isBlank()) {
			return null;
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
