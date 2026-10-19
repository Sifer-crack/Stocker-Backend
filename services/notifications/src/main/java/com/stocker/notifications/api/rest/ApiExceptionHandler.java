package com.stocker.notifications.api.rest;

import com.stocker.notifications.application.NotificationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(NotificationNotFoundException.class)
	public ResponseEntity<Map<String, String>> notFound(NotificationNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
	}
}
