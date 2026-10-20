package com.stocker.pricing.ingest.parse;

import com.stocker.pricing.ingest.ChainId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a chain's category page is read with, in one place: the product-tile selector, where each field sits inside
 * a tile, how the product code is derived, the "Showing A - B of M products" line and the pagination nav. The
 * scraper bodies hold no selectors any more; when a site changes, this is the only class to adjust.
 *
 * <p>The template is evaluated in ONE {@code page.evaluate} (see {@code TemplatedPageLoader}) that returns compact JSON
 * (one small object per tile), so no page HTML travels to Java.
 *
 * <p>{@link #foodstuffs} is shared by New World and PAK'nSAVE (same Sitecore platform); {@link #withField} makes a
 * chain-specific copy. {@link #woolworths} is separate.
 */
public record ChainPageTemplate(
		ChainId chain,
		String cardSelector,
		Map<String, FieldSpec> fields,
		/** Regex applied to the raw {@link #CODE} value; group 1 is the product code. Null when {@link #codeSegmentFromEnd} is used. */
		Pattern codePattern,
		/** N > 0: the code is the N-th "/" segment from the end of the (query-stripped) raw value. */
		int codeSegmentFromEnd,
		/** JS regex source (run case-insensitively over the visible text); null = the chain has no such line. */
		String showingRegex,
		/** CSS selector of the pagination nav; only used to make a missing "Showing" line easier to diagnose. */
		String paginationNavSelector,
		/** Query parameter that carries the page number in the chain's paging links ({@code pg} or {@code page}). */
		String pageParam,
		List<MandatoryField> mandatory,
		/**
		 * True when the tile wrappers can exist before their content does (client-side hydration): the loader then
		 * waits for tiles whose {@link #NAME} field has text instead of reading as soon as the first wrapper appears.
		 */
		boolean waitForNamedTiles) {

	public static final String NAME = "name";
	public static final String BRAND = "brand";
	public static final String PRICE_META = "priceMeta";
	public static final String PRICE_DOLLARS = "priceDollars";
	public static final String PRICE_CENTS = "priceCents";
	public static final String PRICE_TEXT = "priceText";
	public static final String IMAGE = "image";
	/** Raw value the product code is derived from (card test id, or the product link). */
	public static final String CODE = "code";

	/**
	 * Where one value sits inside a tile. {@code selector} "" = the tile itself; {@code attribute} null = text content.
	 */
	public record FieldSpec(String selector, String attribute) {
		public static FieldSpec text(String selector) {
			return new FieldSpec(selector, null);
		}

		public static FieldSpec attr(String selector, String attribute) {
			return new FieldSpec(selector, attribute);
		}
	}

	/**
	 * A value every real tile has. If NO tile of a page yields it, the site layout most likely changed: the scraper
	 * logs a WARN naming this field and its selector instead of quietly storing zero products.
	 */
	public record MandatoryField(String name, String selectorDescription, Function<ProductCardFields, String> value) {
	}

	private static final Pattern FOODSTUFFS_CODE = Pattern.compile("product-(\\d+)-\\w+-\\d+");

	/** New World / PAK'nSAVE. Selectors verified against the live sites (see INGEST_MODULE.md). */
	public static ChainPageTemplate foodstuffs(ChainId chain) {
		if (chain != ChainId.NEWWORLD && chain != ChainId.PAKNSAVE) {
			throw new IllegalArgumentException("Foodstuffs template is for NEWWORLD/PAKNSAVE, got " + chain);
		}
		Map<String, FieldSpec> fields = new LinkedHashMap<>();
		fields.put(NAME, FieldSpec.text("[data-testid='product-title']"));
		fields.put(BRAND, FieldSpec.text("[data-testid='product-subtitle']"));       // the pack size ("2l") lives here
		// PAK'nSAVE omits the schema.org price meta; it renders split dollar / cent spans instead.
		fields.put(PRICE_META, FieldSpec.attr("meta[itemprop='price']", "content"));
		fields.put(PRICE_DOLLARS, FieldSpec.text("[data-testid='price-dollars']"));
		fields.put(PRICE_CENTS, FieldSpec.text("[data-testid='price-cents']"));
		fields.put(IMAGE, FieldSpec.attr("[data-testid='product-image']", "src"));
		fields.put(CODE, FieldSpec.attr("", "data-testid"));                          // "product-5000518-EA-000"
		return new ChainPageTemplate(chain, "div[itemtype='https://schema.org/Product']", fields, FOODSTUFFS_CODE, 0,
				CategoryPagination.SHOWING_SOURCE, "nav[aria-label='pagination']", CategoryPagination.FOODSTUFFS_PAGE_PARAM, List.of(
						new MandatoryField(NAME, "[data-testid='product-title']", ProductCardFields::rawName),
						new MandatoryField(CODE, "card data-testid like product-<digits>-<unit>-<n>",
								ProductCardFields::nativeProductCode),
						new MandatoryField("price", "meta[itemprop='price'] or [data-testid='price-dollars'] + price-cents",
								ProductCardFields::rawPriceText)),
				false);
	}

	/**
	 * Woolworths NZ: 36 listed products per page, paged with {@code ?page=N} and a "1 to 36 of 264 products" line (live
	 * probe 2026-10-20, see INGEST_MODULE.md). Class PREFIXES survive the hashed suffixes (Jason-nzd/countdown-scraper); re-verified against the live
	 * page on 2026-10-20 (see INGEST_MODULE.md): product links are now /shop/product-details/ (with a hyphen), which the
	 * code segment logic does not depend on.
	 */
	public static ChainPageTemplate woolworths() {
		Map<String, FieldSpec> fields = new LinkedHashMap<>();
		fields.put(NAME, FieldSpec.text("a[class^='product-tile_description_']"));
		fields.put(PRICE_TEXT, FieldSpec.text("span[class^='product-price_value_']"));
		fields.put(IMAGE, FieldSpec.attr("a[class^='product-tile_productImageLink_'] img", "src"));
		fields.put(CODE, FieldSpec.attr("a[class^='product-tile_productImageLink_']", "href"));  // .../<stockcode>/<slug>
		return new ChainPageTemplate(ChainId.WOOLWORTHS, "div[class^='product-tile_imgAndTextArea_']", fields, null, 2,
				CategoryPagination.WOOLWORTHS_SOURCE, "nav[aria-label='Pagination' i]", CategoryPagination.WOOLWORTHS_PAGE_PARAM, List.of(
						new MandatoryField(NAME, "a[class^='product-tile_description_']", ProductCardFields::rawName),
						new MandatoryField(CODE, "a[class^='product-tile_productImageLink_'] href (stockcode segment)",
								ProductCardFields::nativeProductCode),
						new MandatoryField("price", "span[class^='product-price_value_']", ProductCardFields::rawPriceText)),
				true);
	}

	/** Card selector plus every field selector, for log lines. */
	public String describeSelectors() {
		StringBuilder sb = new StringBuilder("card=").append(cardSelector);
		fields.forEach((name, f) -> sb.append(", ").append(name).append('=').append(f.selector().isEmpty() ? "(card)" : f.selector())
				.append(f.attribute() == null ? "" : "@" + f.attribute()));
		return sb.toString();
	}

	/** A copy with one field spec replaced (chain-specific override of a shared template). */
	public ChainPageTemplate withField(String field, FieldSpec spec) {
		Map<String, FieldSpec> copy = new LinkedHashMap<>(fields);
		copy.put(field, spec);
		return new ChainPageTemplate(chain, cardSelector, copy, codePattern, codeSegmentFromEnd, showingRegex,
				paginationNavSelector, pageParam, mandatory, waitForNamedTiles);
	}

	/** The argument of the single {@code page.evaluate}: plain maps / lists / strings only. */
	public Map<String, Object> toEvaluateArgument() {
		Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("card", cardSelector);
		Map<String, Object> fieldMap = new LinkedHashMap<>();
		fields.forEach((name, f) -> {
			Map<String, Object> one = new LinkedHashMap<>();
			one.put("selector", f.selector());
			one.put("attribute", f.attribute());
			fieldMap.put(name, one);
		});
		spec.put("fields", fieldMap);
		spec.put("showing", showingRegex);
		spec.put("nav", paginationNavSelector);
		return spec;
	}

	/** Product code from the raw {@link #CODE} value, or null when it does not have the expected shape. */
	public String nativeCode(String raw) {
		if (raw == null) {
			return null;
		}
		if (codePattern != null) {
			Matcher matcher = codePattern.matcher(raw);
			return matcher.matches() ? matcher.group(1) : null;
		}
		if (codeSegmentFromEnd > 0) {
			String withoutQuery = raw.contains("?") ? raw.substring(0, raw.indexOf('?')) : raw;
			String[] segments = withoutQuery.split("/");
			return segments.length >= 2 && segments.length >= codeSegmentFromEnd
					? segments[segments.length - codeSegmentFromEnd] : null;
		}
		return raw;
	}

	/** Price text of one tile as the extractors expect it: schema.org meta, else "dollars.cents", else the price text. */
	public String priceText(Map<String, String> tile) {
		String meta = tile.get(PRICE_META);
		if (meta != null && !meta.isBlank()) {
			return meta;
		}
		String dollars = tile.get(PRICE_DOLLARS);
		String cents = tile.get(PRICE_CENTS);
		if (dollars != null && cents != null) {
			return dollars.trim() + "." + cents.trim();
		}
		return fields.containsKey(PRICE_TEXT) ? tile.get(PRICE_TEXT) : null;
	}
}
