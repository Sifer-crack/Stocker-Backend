package com.stocker.pricing.normalize;

/** Base unit a pack size is stored in: grams, millilitres, or a count of items. */
public enum PackUnit {
	G("g"), ML("ml"), EACH("each");

	private final String code;

	PackUnit(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public static PackUnit fromCode(String code) {
		for (PackUnit unit : values()) {
			if (unit.code.equals(code)) {
				return unit;
			}
		}
		return null;
	}
}
