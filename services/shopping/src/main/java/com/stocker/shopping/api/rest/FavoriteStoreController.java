package com.stocker.shopping.api.rest;

import com.stocker.shopping.api.rest.dto.AddFavoriteStoreRequest;
import com.stocker.shopping.application.FavoriteStoreService;
import com.stocker.shopping.domain.FavoriteStore;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The user's favorite stores. Reached through the gateway ({@code /api/shopping/favorite-stores}); the user
 * is the {@code X-User-Id} the gateway sets from the verified JWT. A store's products come from pricing
 * ({@code GET /api/pricing/stores/{chainId}/products}); items are added with {@code POST /shopping/items}.
 */
@RestController
@RequestMapping("/shopping/favorite-stores")
public class FavoriteStoreController {

	private static final String USER_HEADER = "X-User-Id";

	private final FavoriteStoreService service;

	public FavoriteStoreController(FavoriteStoreService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<FavoriteStoreResponse> add(@RequestHeader(USER_HEADER) String userId,
			@Valid @RequestBody AddFavoriteStoreRequest body) {
		FavoriteStore saved = service.add(requireUser(userId), body.chainId(), body.storeId());
		return ResponseEntity.status(HttpStatus.CREATED).body(FavoriteStoreResponse.from(saved));
	}

	@GetMapping
	public List<FavoriteStoreResponse> list(@RequestHeader(USER_HEADER) String userId) {
		return service.list(requireUser(userId)).stream().map(FavoriteStoreResponse::from).toList();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> remove(@RequestHeader(USER_HEADER) String userId, @PathVariable UUID id) {
		service.remove(requireUser(userId), id);
		return ResponseEntity.noContent().build();
	}

	private static String requireUser(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing user identity");
		}
		return userId;
	}

	public record FavoriteStoreResponse(UUID id, String chainId, String storeId, OffsetDateTime createdAt) {

		static FavoriteStoreResponse from(FavoriteStore store) {
			return new FavoriteStoreResponse(store.getId(), store.getChainId(), store.getStoreId(), store.getCreatedAt());
		}
	}
}
