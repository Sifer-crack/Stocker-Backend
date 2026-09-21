package com.stocker.shopping.api.rest;

import com.stocker.shopping.api.rest.dto.AddItemRequest;
import com.stocker.shopping.application.AddItemCommand;
import com.stocker.shopping.application.ShoppingItemService;
import com.stocker.shopping.application.model.ItemView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reached through the gateway ({@code /api/shopping/**}, prefix stripped), which authenticates the
 * caller and forwards their id as {@code X-User-Id}. Adding an item returns immediately with
 * comparisonStatus "pending"; the comparison arrives later through the gateway push.
 */
@RestController
@RequestMapping("/shopping/items")
public class ShoppingItemController {

	static final String USER_HEADER = "X-User-Id";
	static final String HOUSEHOLD_HEADER = "X-Household-Id";

	private final ShoppingItemService service;

	public ShoppingItemController(ShoppingItemService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<ItemView> add(
			@RequestHeader(USER_HEADER) String userId,
			@RequestHeader(value = HOUSEHOLD_HEADER, required = false) String householdId,
			@Valid @RequestBody AddItemRequest body) {
		ItemView created = service.addItem(new AddItemCommand(
				requireUser(userId),
				blankToNull(householdId),
				body.name().trim(),
				blankToNull(body.sku()),
				blankToNull(body.category()),
				blankToNull(body.region()),
				body.quantity() == null ? 1 : body.quantity()));
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@GetMapping
	public List<ItemView> list(@RequestHeader(USER_HEADER) String userId) {
		return service.listForUser(requireUser(userId));
	}

	@GetMapping("/{id}")
	public ItemView get(@RequestHeader(USER_HEADER) String userId, @PathVariable UUID id) {
		return service.findForUser(id, requireUser(userId))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "item not found"));
	}

	private static String requireUser(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user identity");
		}
		return userId;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
