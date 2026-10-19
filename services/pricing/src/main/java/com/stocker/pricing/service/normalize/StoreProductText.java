package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.PriceRecord;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads a scraped price_records row back into shopper-readable text. New World / PAK'nSave scrape
 * the card's subtitle (the pack size, e.g. "2l") into the brand slot, so a size-shaped brand is
 * treated as part of the name instead.
 */
public final class StoreProductText {

	private static final Pattern SIZE_LIKE = Pattern.compile("^\\d+(?:\\.\\d+)?\\s*(?:l|ml|g|kg)$", Pattern.CASE_INSENSITIVE);

	private StoreProductText() {
	}

	/** The product name plus the pack size when the chain lists it separately; null if the row has no name. */
	public static String name(PriceRecord row) {
		String name = attribute(row, "name");
		if (name == null) {
			return null;
		}
		String size = packSize(row);
		return size == null || name.toLowerCase(Locale.ROOT).contains(size.toLowerCase(Locale.ROOT))
				? name
				: name + " " + size;
	}

	public static String realBrand(PriceRecord row) {
		return packSize(row) == null ? attribute(row, "brand") : null;
	}

	private static String packSize(PriceRecord row) {
		String brand = attribute(row, "brand");
		return brand != null && SIZE_LIKE.matcher(brand.trim()).matches() ? brand.trim() : null;
	}

	public static String attribute(PriceRecord row, String key) {
		Object value = row.getRawAttributes() == null ? null : row.getRawAttributes().get(key);
		return value == null || value.toString().isBlank() ? null : value.toString();
	}
}
