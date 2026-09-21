package com.stocker.shopping.infrastructure.persistence;

import com.stocker.shopping.domain.ShoppingItemPrice;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShoppingItemPriceRepository extends JpaRepository<ShoppingItemPrice, Long> {

	List<ShoppingItemPrice> findByItemId(UUID itemId);

	List<ShoppingItemPrice> findByItemIdIn(Collection<UUID> itemIds);
}
