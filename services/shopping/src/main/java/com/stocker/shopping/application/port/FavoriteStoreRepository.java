package com.stocker.shopping.application.port;

import com.stocker.shopping.domain.FavoriteStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteStoreRepository {

	FavoriteStore save(FavoriteStore store);

	/** The user's favorites, oldest first. */
	List<FavoriteStore> findByUserId(String userId);

	/** {@code storeId} null means "the whole chain". */
	Optional<FavoriteStore> findByUserIdAndChainIdAndStoreId(String userId, String chainId, String storeId);

	Optional<FavoriteStore> findByIdAndUserId(UUID id, String userId);

	void delete(FavoriteStore store);
}
