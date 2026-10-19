package com.stocker.shopping.application;

import com.stocker.shopping.application.port.FavoriteStoreRepository;
import com.stocker.shopping.domain.FavoriteStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteStoreService {

	private final FavoriteStoreRepository repository;
	private final Clock clock;

	public FavoriteStoreService(FavoriteStoreRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	/** Saves a favorite; saving the same chain/store again just returns the existing one. */
	@Transactional
	public FavoriteStore add(String userId, String chainId, String storeId) {
		String chain = FavoriteStore.normaliseChain(chainId);
		String store = storeId == null || storeId.isBlank() ? null : storeId.trim();
		return repository.findByUserIdAndChainIdAndStoreId(userId, chain, store)
				.orElseGet(() -> repository.save(FavoriteStore.create(userId, chain, store, OffsetDateTime.now(clock))));
	}

	@Transactional(readOnly = true)
	public List<FavoriteStore> list(String userId) {
		return repository.findByUserId(userId);
	}

	/** Removes the user's own favorite; someone else's (or an unknown id) is "not found". */
	@Transactional
	public void remove(String userId, UUID id) {
		FavoriteStore store = repository.findByIdAndUserId(id, userId)
				.orElseThrow(() -> new FavoriteStoreNotFoundException(id));
		repository.delete(store);
	}
}
