package com.stocker.pricing.normalize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** A pack size in base units (grams, millilitres or a count), e.g. "2L" = 2000 ML. */
public record PackSize(PackUnit unit, BigDecimal quantity) {

	private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

	public static PackSize ofWeightKg(BigDecimal kg) {
		return kg == null || kg.signum() <= 0 ? null : new PackSize(PackUnit.G, kg.multiply(THOUSAND));
	}

	public static PackSize ofVolumeL(BigDecimal litres) {
		return litres == null || litres.signum() <= 0 ? null : new PackSize(PackUnit.ML, litres.multiply(THOUSAND));
	}

	/** Price per kg / per L / per item, or null when the size is unusable. */
	public BigDecimal unitPrice(BigDecimal price) {
		if (price == null || quantity == null || quantity.signum() <= 0) {
			return null;
		}
		BigDecimal perBase = unit == PackUnit.EACH ? quantity : quantity.divide(THOUSAND);
		return price.divide(perBase, 4, RoundingMode.HALF_UP);
	}

	public boolean sameAs(PackSize other) {
		return other != null && unit == other.unit && quantity.compareTo(other.quantity) == 0;
	}

	public String label() {
		return switch (unit) {
			case G -> quantity.compareTo(THOUSAND) >= 0 ? plain(quantity.divide(THOUSAND)) + "kg" : plain(quantity) + "g";
			case ML -> quantity.compareTo(THOUSAND) >= 0 ? plain(quantity.divide(THOUSAND)) + "L" : plain(quantity) + "ml";
			case EACH -> plain(quantity) + " pack";
		};
	}

	private static String plain(BigDecimal value) {
		return value.stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
	}
}
