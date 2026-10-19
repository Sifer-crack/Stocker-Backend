package com.stocker.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A supermarket a user saved as a favorite: a chain, optionally narrowed to one store of it. Chains are the
 * same ids pricing stores prices under.
 */
@Entity
@Table(name = "favorite_stores")
public class FavoriteStore {

	/** Must match pricing's ChainId (lower-cased); shopping does not depend on pricing's code. */
	public static final Set<String> SUPPORTED_CHAINS = Set.of("newworld", "paknsave", "woolworths");

	@Id
	@Column(name = "id")
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "chain_id", nullable = false)
	private String chainId;

	@Column(name = "store_id")
	private String storeId;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime createdAt;

	protected FavoriteStore() {
	}

	public static FavoriteStore create(String userId, String chainId, String storeId, OffsetDateTime now) {
		String chain = normaliseChain(chainId);
		FavoriteStore store = new FavoriteStore();
		store.id = UUID.randomUUID();
		store.userId = userId;
		store.chainId = chain;
		store.storeId = storeId == null || storeId.isBlank() ? null : storeId.trim();
		store.createdAt = now;
		return store;
	}

	/** Trims and lower-cases the chain id and checks it is one pricing knows. */
	public static String normaliseChain(String chainId) {
		String chain = chainId == null ? "" : chainId.trim().toLowerCase(Locale.ROOT);
		if (!SUPPORTED_CHAINS.contains(chain)) {
			throw new IllegalArgumentException("Unknown store chain '" + (chainId == null ? "" : chainId.trim())
					+ "'; expected one of " + SUPPORTED_CHAINS.stream().sorted().toList());
		}
		return chain;
	}

	public UUID getId() {
		return id;
	}

	public String getUserId() {
		return userId;
	}

	public String getChainId() {
		return chainId;
	}

	public String getStoreId() {
		return storeId;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
