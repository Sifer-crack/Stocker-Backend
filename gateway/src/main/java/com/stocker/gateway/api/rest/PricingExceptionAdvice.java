package com.stocker.gateway.api.rest;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * One mapping from pricing's gRPC statuses to HTTP for every pricing-backed controller: INVALID_ARGUMENT 422,
 * NOT_FOUND 404, UNAVAILABLE 503, DEADLINE_EXCEEDED 504, anything else 500 with a generic message (the internal
 * description is logged, not returned). The JSON body is {@code {"message": ..., "error": ...}}; {@code error} is the
 * original field the frontend already reads, {@code message} is the same text.
 */
@RestControllerAdvice(assignableTypes = {PricingController.class, ProductSearchController.class,
		StoreProductsController.class})
public class PricingExceptionAdvice {

	private static final Logger log = LoggerFactory.getLogger(PricingExceptionAdvice.class);

	@ExceptionHandler(StatusRuntimeException.class)
	public ResponseEntity<ErrorBody> handleGrpc(StatusRuntimeException ex) {
		Status status = ex.getStatus();
		String description = status.getDescription();
		return switch (status.getCode()) {
			case INVALID_ARGUMENT -> body(HttpStatus.UNPROCESSABLE_CONTENT, description, "invalid request");
			case NOT_FOUND -> body(HttpStatus.NOT_FOUND, description, "not found");
			case UNAVAILABLE -> body(HttpStatus.SERVICE_UNAVAILABLE, description, "pricing is unavailable");
			case DEADLINE_EXCEEDED -> body(HttpStatus.GATEWAY_TIMEOUT, description, "pricing timed out");
			default -> {
				log.warn("Unexpected pricing gRPC status {}: {}", status.getCode(), description);
				yield body(HttpStatus.INTERNAL_SERVER_ERROR, "pricing request failed", "pricing request failed");
			}
		};
	}

	/** Keeps the message in the body (WebFlux omits a ResponseStatusException's reason by default). */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ErrorBody> handleStatus(ResponseStatusException ex) {
		return ResponseEntity.status(ex.getStatusCode()).body(new ErrorBody(ex.getReason(), ex.getReason()));
	}

	private static ResponseEntity<ErrorBody> body(HttpStatus status, String description, String fallback) {
		String text = description == null || description.isBlank() ? fallback : description;
		return ResponseEntity.status(status).body(new ErrorBody(text, text));
	}

	public record ErrorBody(String message, String error) {
	}
}
