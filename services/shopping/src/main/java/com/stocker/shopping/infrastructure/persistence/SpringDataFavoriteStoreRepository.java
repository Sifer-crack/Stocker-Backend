package com.stocker.shopping.infrastructure.persistence;

import com.stocker.shopping.domain.FavoriteStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataFavoriteStoreRepository extends JpaRepository<FavoriteStore, UUID> {

	List<FavoriteStore> findByUserIdOrderByCreatedAtAsc(String userId);

	Optional<FavoriteStore> findByUserIdAndChainIdAndStoreId(String userId, String chainId, String storeId);

	Optional<FavoriteStore> findByUserIdAndChainIdAndStoreIdIsNull(String userId, String chainId);

	Optional<FavoriteStore> findByIdAndUserId(UUID id, String userId);
}
