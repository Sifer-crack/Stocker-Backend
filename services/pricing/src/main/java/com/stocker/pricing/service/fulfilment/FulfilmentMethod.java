package com.stocker.pricing.service.fulfilment;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Ways to get a chain's groceries. Declaration order is the tie-break order when two methods cost the same:
 * click_and_collect, then delivery.
 */
public enum FulfilmentMethod {
	CLICK_AND_COLLECT("click_and_collect"),
	DELIVERY("delivery");

	private final String wireName;

	FulfilmentMethod(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	/** @throws IllegalArgumentException with a message listing the valid names when {@code name} is unknown */
	public static FulfilmentMethod fromWireName(String name) {
		String trimmed = name == null ? "" : name.trim();
		for (FulfilmentMethod method : values()) {
			if (method.wireName.equalsIgnoreCase(trimmed)) {
				return method;
			}
		}
		throw new IllegalArgumentException("Unknown fulfilment method '" + trimmed + "'. Valid methods: "
				+ Arrays.stream(values()).map(FulfilmentMethod::wireName).collect(Collectors.joining(", ")));
	}
}
