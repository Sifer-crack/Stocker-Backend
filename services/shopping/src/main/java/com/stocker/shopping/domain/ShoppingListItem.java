package com.stocker.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A shopping-list item and the outcome of its price comparison. New items start {@code PENDING};
 * the comparison fills in {@code cheapest*} and flips the status to {@code AVAILABLE}. The full
 * list of compared prices lives in {@link ShoppingItemPrice}.
 */
@Entity
@Table(name = "shopping_list_items")
public class ShoppingListItem {

	@Id
	@Column(name = "id")
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "household_id")
	private String householdId;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "sku")
	private String sku;

	@Column(name = "category")
	private String category;

	@Column(name = "region")
	private String region;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Enumerated(EnumType.STRING)
	@Column(name = "comparison_status", nullable = false)
	private ComparisonStatus comparisonStatus;

	@Column(name = "cheapest_chain_id")
	private String cheapestChainId;

	@Column(name = "cheapest_store_id")
	private String cheapestStoreId;

	@Column(name = "cheapest_price", precision = 10, scale = 2)
	private BigDecimal cheapestPrice;

	@Column(name = "currency")
	private String currency;

	@Column(name = "cheapest_promo_flag", nullable = false)
	private boolean cheapestPromoFlag;

	@Column(name = "compared_at", columnDefinition = "timestamptz")
	private OffsetDateTime comparedAt;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime updatedAt;

	protected ShoppingListItem() {
	}

	public static ShoppingListItem create(String userId, String householdId, String name, String sku,
			String category, String region, int quantity, OffsetDateTime now) {
		ShoppingListItem item = new ShoppingListItem();
		item.id = UUID.randomUUID();
		item.userId = userId;
		item.householdId = householdId;
		item.name = name;
		item.sku = sku;
		item.category = category;
		item.region = region;
		item.quantity = quantity;
		item.comparisonStatus = ComparisonStatus.PENDING;
		item.createdAt = now;
		item.updatedAt = now;
		return item;
	}

	/** True when applying this cheapest price would change what a client sees. */
	public boolean cheapestDiffersFrom(ShoppingItemPrice cheapest) {
		return comparisonStatus != ComparisonStatus.AVAILABLE
				|| !Objects.equals(cheapestChainId, cheapest.getChainId())
				|| !Objects.equals(cheapestStoreId, cheapest.getStoreId())
				|| cheapestPrice == null
				|| cheapestPrice.compareTo(cheapest.getPriceAmount()) != 0
				|| cheapestPromoFlag != cheapest.isPromoFlag();
	}

	public void applyCheapest(ShoppingItemPrice cheapest, OffsetDateTime now) {
		this.cheapestChainId = cheapest.getChainId();
		this.cheapestStoreId = cheapest.getStoreId();
		this.cheapestPrice = cheapest.getPriceAmount();
		this.currency = cheapest.getCurrency();
		this.cheapestPromoFlag = cheapest.isPromoFlag();
		this.comparisonStatus = ComparisonStatus.AVAILABLE;
		this.comparedAt = now;
		this.updatedAt = now;
	}

	/** Only ever moves a not-yet-compared item; a stored comparison is never discarded by a rejection. */
	public boolean markUnavailable(OffsetDateTime now) {
		if (comparisonStatus == ComparisonStatus.UNAVAILABLE || comparisonStatus == ComparisonStatus.AVAILABLE) {
			return false;
		}
		this.comparisonStatus = ComparisonStatus.UNAVAILABLE;
		this.updatedAt = now;
		return true;
	}

	public UUID getId() {
		return id;
	}

	public String getUserId() {
		return userId;
	}

	public String getHouseholdId() {
		return householdId;
	}

	public String getName() {
		return name;
	}

	public String getSku() {
		return sku;
	}

	public String getCategory() {
		return category;
	}

	public String getRegion() {
		return region;
	}

	public int getQuantity() {
		return quantity;
	}

	public ComparisonStatus getComparisonStatus() {
		return comparisonStatus;
	}

	public String getCheapestChainId() {
		return cheapestChainId;
	}

	public String getCheapestStoreId() {
		return cheapestStoreId;
	}

	public BigDecimal getCheapestPrice() {
		return cheapestPrice;
	}

	public String getCurrency() {
		return currency;
	}

	public boolean isCheapestPromoFlag() {
		return cheapestPromoFlag;
	}

	public OffsetDateTime getComparedAt() {
		return comparedAt;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}
}
