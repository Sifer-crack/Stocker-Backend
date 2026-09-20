package com.stocker.pricing.service.servicearea;

/**
 * Signals that a request cannot be served because it falls outside Stocker's supported service
 * area - either an explicitly unsupported region, or an empty shopping list with no options to
 * find suitable stores for. Both call sites share this one exception and message on purpose: the
 * two acceptance criteria this satisfies require the same displayed error text.
 */
public final class ServiceAreaException extends RuntimeException {

	public static final String OUTSIDE_SERVICE_AREA_MESSAGE =
			"You are outside Stocker's service area. Try again from a supported region.";

	private ServiceAreaException(String message) {
		super(message);
	}

	public static ServiceAreaException outsideServiceArea() {
		return new ServiceAreaException(OUTSIDE_SERVICE_AREA_MESSAGE);
	}
}
