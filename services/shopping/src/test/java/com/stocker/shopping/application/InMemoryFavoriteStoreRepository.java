package com.stocker.shopping.application;

import com.stocker.shopping.application.port.FavoriteStoreRepository;
import com.stocker.shopping.domain.FavoriteStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Test fake for the repository port: real services without a database. */
public class InMemoryFavoriteStoreRepository implements FavoriteStoreRepository {

	private final Map<UUID, FavoriteStore> rows = new LinkedHashMap<>();

	@Override
	public FavoriteStore save(FavoriteStore store) {
		rows.put(store.getId(), store);
		return store;
	}

	@Override
	public List<FavoriteStore> findByUserId(String userId) {
		List<FavoriteStore> found = new ArrayList<>(rows.values().stream()
				.filter(row -> row.getUserId().equals(userId)).toList());
		found.sort(Comparator.comparing(FavoriteStore::getCreatedAt));
		return found;
	}

	@Override
	public Optional<FavoriteStore> findByUserIdAndChainIdAndStoreId(String userId, String chainId, String storeId) {
		return rows.values().stream().filter(row -> row.getUserId().equals(userId)
				&& row.getChainId().equals(chainId) && Objects.equals(row.getStoreId(), storeId)).findFirst();
	}

	@Override
	public Optional<FavoriteStore> findByIdAndUserId(UUID id, String userId) {
		return Optional.ofNullable(rows.get(id)).filter(row -> row.getUserId().equals(userId));
	}

	@Override
	public void delete(FavoriteStore store) {
		rows.remove(store.getId());
	}

	public int size() {
		return rows.size();
	}
}
