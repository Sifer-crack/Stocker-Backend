package com.stocker.shopping.infrastructure.persistence;

import com.stocker.shopping.domain.ShoppingListItem;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShoppingListItemRepository extends JpaRepository<ShoppingListItem, UUID> {

	List<ShoppingListItem> findByUserIdOrderByCreatedAtDesc(String userId);

	/**
	 * Row-locking read for comparison updates: the synchronous gRPC result and a Kafka backfill can
	 * target the same item at the same moment, and both do read-modify-write on it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from ShoppingListItem i where i.id = :id")
	Optional<ShoppingListItem> findByIdForUpdate(@Param("id") UUID id);
}
