package com.stocker.pricing.ingest.parse;

import com.stocker.pricing.fetch.model.RawProduct;
import com.stocker.pricing.ingest.ChainId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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

	/** One page's cards: the usable products and how many cards lacked each field (a card can lack several). */
	public record PageParse(List<RawProduct> products, int cards, int unusable, int missingName, int missingCode,
			int missingPrice) {

		/** The single per-page WARN text; null when every card was usable. */
		public String summary(String url, String templateSelectors) {
			if (unusable == 0) {
				return null;
			}
			return "WOOLWORTHS: " + unusable + " of " + cards + " cards unusable at " + url + " (fields missing: name "
					+ missingName + ", code " + missingCode + ", price " + missingPrice + "); template selectors: "
					+ templateSelectors;
		}

		/** Cards were found on a page that loaded fine, but none gave a product: the template is stale or the tiles never hydrated. */
		public boolean templateStale() {
			return cards > 0 && products.isEmpty();
		}
	}

	/** Parses every card of a page without a log line per bad card; the caller logs {@link PageParse#summary}. */
	public static PageParse parseAll(List<ProductCardFields> cards, String storeId) {
		List<RawProduct> products = new ArrayList<>();
		int noName = 0;
		int noCode = 0;
		int noPrice = 0;
		int unusable = 0;
		for (ProductCardFields fields : cards) {
			boolean nameMissing = fields.rawName() == null || fields.rawName().isBlank();
			boolean codeMissing = fields.nativeProductCode() == null || fields.nativeProductCode().isBlank();
			boolean priceMissing = parsePrice(fields.rawPriceText()) == null;
			if (nameMissing) {
				noName++;
			}
			if (codeMissing) {
				noCode++;
			}
			if (priceMissing) {
				noPrice++;
			}
			Optional<RawProduct> product = nameMissing || codeMissing || priceMissing ? Optional.empty()
					: parse(fields, storeId);
			if (product.isPresent()) {
				products.add(product.get());
			} else {
				unusable++;
			}
		}
		return new PageParse(products, cards.size(), unusable, noName, noCode, noPrice);
	}

	public static Optional<RawProduct> parse(ProductCardFields fields, String storeId) {
		if (fields.rawName() == null || fields.rawName().isBlank()) {
			log.debug("Skipping Woolworths product card with no name (stockcode={})", fields.nativeProductCode());
			return Optional.empty();
		}
		if (fields.nativeProductCode() == null || fields.nativeProductCode().isBlank()) {
			log.debug("Skipping Woolworths product card with no stockcode (name={})", fields.rawName());
			return Optional.empty();
		}

		BigDecimal price = parsePrice(fields.rawPriceText());
		if (price == null) {
			log.debug("Skipping Woolworths product card with unparsable price (stockcode={}, rawPriceText={})",
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
