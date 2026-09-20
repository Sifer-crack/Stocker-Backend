package com.stocker.pricing.ingest.model;

public class ScrapeException extends RuntimeException {

	public ScrapeException(String message, Throwable cause) {
		super(message, cause);
	}

	public ScrapeException(String message) {
		super(message);
	}
}
