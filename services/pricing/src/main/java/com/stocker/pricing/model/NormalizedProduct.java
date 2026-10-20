package com.stocker.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Derived from price_records (see PriceStatsService); one row per (itemId, storeId). */
@Entity
@Table(name = "normalized_products")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedProduct {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "item_id", nullable = false)
	private String itemId;

	@Column(name = "store_id", nullable = false)
	private String storeId;

	@Column(name = "chain_id", nullable = false)
	private String chainId;

	@Column(name = "canonical_key")
	private String canonicalKey;

	@Column(name = "normalized_name", nullable = false)
	private String normalizedName;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "brand")
	private String brand;

	@Column(name = "pack_quantity", precision = 12, scale = 3)
	private BigDecimal packQuantity;

	@Column(name = "pack_unit")
	private String packUnit;

	@Column(name = "price_amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal priceAmount;

	@Column(name = "unit_price", precision = 12, scale = 4)
	private BigDecimal unitPrice;

	@Column(name = "currency", nullable = false)
	private String currency;

	@Column(name = "promo_flag", nullable = false)
	private boolean promoFlag;

	@Column(name = "captured_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime capturedAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime updatedAt;
}
