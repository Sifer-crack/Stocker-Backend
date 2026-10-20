package com.stocker.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One compared price for a shopping-list item at a specific (chain, store). */
@Entity
@Table(name = "shopping_item_prices")
public class ShoppingItemPrice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "item_id", nullable = false)
	private UUID itemId;

	@Column(name = "chain_id", nullable = false)
	private String chainId;

	@Column(name = "store_id", nullable = false)
	private String storeId;

	@Column(name = "price_amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal priceAmount;

	@Column(name = "currency", nullable = false)
	private String currency;

	@Column(name = "promo_flag", nullable = false)
	private boolean promoFlag;

	@Column(name = "captured_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime capturedAt;

	protected ShoppingItemPrice() {
	}

	public static ShoppingItemPrice create(UUID itemId, String chainId, String storeId, BigDecimal priceAmount,
			String currency, boolean promoFlag, OffsetDateTime capturedAt) {
		ShoppingItemPrice price = new ShoppingItemPrice();
		price.itemId = itemId;
		price.chainId = chainId;
		price.storeId = storeId;
		price.priceAmount = priceAmount;
		price.currency = currency;
		price.promoFlag = promoFlag;
		price.capturedAt = capturedAt;
		return price;
	}

	/** True when the observable price data (not just the timestamp) would change. */
	public boolean differsFrom(BigDecimal newPrice, String newCurrency, boolean newPromoFlag) {
		return priceAmount.compareTo(newPrice) != 0 || !currency.equals(newCurrency) || promoFlag != newPromoFlag;
	}

	public void update(BigDecimal newPrice, String newCurrency, boolean newPromoFlag, OffsetDateTime newCapturedAt) {
		this.priceAmount = newPrice;
		this.currency = newCurrency;
		this.promoFlag = newPromoFlag;
		this.capturedAt = newCapturedAt;
	}

	public Long getId() {
		return id;
	}

	public UUID getItemId() {
		return itemId;
	}

	public String getChainId() {
		return chainId;
	}

	public String getStoreId() {
		return storeId;
	}

	public BigDecimal getPriceAmount() {
		return priceAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public boolean isPromoFlag() {
		return promoFlag;
	}

	public OffsetDateTime getCapturedAt() {
		return capturedAt;
	}
}
