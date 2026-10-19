package com.stocker.pricing.service.normalize;

import com.stocker.pricing.model.PriceRecord;
import com.stocker.pricing.normalize.PackSizeParser;
import java.util.Locale;

/**
 * Reads a scraped price_records row back into shopper-readable text. New World / PAK'nSave scrape
 * the card's subtitle (the pack size, e.g. "2l") into the brand slot, so a size-shaped brand is
 * treated as part of the name instead. That covers unit sizes ("2l", "12 x 330ml") and pack counts
 * ("30pk", "12 pack", "dozen"), which the same chains put in the brand slot for eggs, rolls and multipacks.
 */
public final class StoreProductText {

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
		return brand != null && sizeLike(brand) ? brand.trim() : null;
	}

	/** True when the whole text is a pack size or pack count and nothing else ("2l", "30pk", "12 pack", "dozen"). */
	public static boolean sizeLike(String text) {
		return text != null && !text.isBlank() && PackSizeParser.parse(text.trim()).isPresent()
				&& PackSizeParser.strip(text).replaceAll("[^a-z0-9]+", "").isEmpty();
	}

	public static String attribute(PriceRecord row, String key) {
		Object value = row.getRawAttributes() == null ? null : row.getRawAttributes().get(key);
		return value == null || value.toString().isBlank() ? null : value.toString();
	}
}
