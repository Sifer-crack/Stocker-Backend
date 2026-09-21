package com.stocker.shopping.application.port;

public class PricingRejectedException extends RuntimeException {

	public PricingRejectedException(String message) {
		super(message);
	}
}
