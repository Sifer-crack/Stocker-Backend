package com.stocker.shopping.infrastructure.persistence;

import com.stocker.shopping.application.port.FavoriteStoreRepository;
import com.stocker.shopping.domain.FavoriteStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class JpaFavoriteStoreRepository implements FavoriteStoreRepository {

	private final SpringDataFavoriteStoreRepository repository;

	public JpaFavoriteStoreRepository(SpringDataFavoriteStoreRepository repository) {
		this.repository = repository;
	}

	@Override
	public FavoriteStore save(FavoriteStore store) {
		return repository.save(store);
	}

	@Override
	public List<FavoriteStore> findByUserId(String userId) {
		return repository.findByUserIdOrderByCreatedAtAsc(userId);
	}

	@Override
	public Optional<FavoriteStore> findByUserIdAndChainIdAndStoreId(String userId, String chainId, String storeId) {
		// "= NULL" never matches in SQL, so a whole-chain favorite needs its own IS NULL query
		return storeId == null
				? repository.findByUserIdAndChainIdAndStoreIdIsNull(userId, chainId)
				: repository.findByUserIdAndChainIdAndStoreId(userId, chainId, storeId);
	}

	@Override
	public Optional<FavoriteStore> findByIdAndUserId(UUID id, String userId) {
		return repository.findByIdAndUserId(id, userId);
	}

	@Override
	public void delete(FavoriteStore store) {
		repository.delete(store);
	}
}
