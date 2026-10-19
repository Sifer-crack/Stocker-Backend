package com.stocker.notifications.api.rest;

import com.stocker.notifications.application.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** The caller is identified by X-User-Id, which the gateway sets from the verified JWT (never from the client). */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@GetMapping
	public List<NotificationResponse> list(@RequestHeader(value = "X-User-Id", required = false) String userHeader) {
		return notificationService.list(requireUser(userHeader)).stream().map(NotificationResponse::from).toList();
	}

	@PostMapping("/{id}/acknowledge")
	public NotificationResponse acknowledge(@RequestHeader(value = "X-User-Id", required = false) String userHeader,
			@PathVariable UUID id) {
		return NotificationResponse.from(notificationService.acknowledge(requireUser(userHeader), id));
	}

	private static UUID requireUser(String header) {
		if (header == null || header.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user");
		}
		try {
			return UUID.fromString(header.trim());
		}
		catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid user");
		}
	}
}
