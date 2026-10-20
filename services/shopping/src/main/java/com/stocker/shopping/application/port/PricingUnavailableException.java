package com.stocker.shopping.application.port;

public class PricingUnavailableException extends RuntimeException {

	public PricingUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
