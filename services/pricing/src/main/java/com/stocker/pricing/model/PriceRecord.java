package com.stocker.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "price_records")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceRecord {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "item_id", nullable = false)
	private String itemId;

	@Column(name = "store_id", nullable = false)
	private String storeId;

	@Column(name = "chain_id", nullable = false)
	private String chainId;

	@Column(name = "channel", nullable = false)
	private String channel;

	@Column(name = "price_amount", nullable = false, precision = 10, scale = 2)
	private BigDecimal priceAmount;

	@Column(name = "currency", nullable = false)
	private String currency;

	@Column(name = "promo_flag", nullable = false)
	private boolean promoFlag;

	@Column(name = "captured_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime capturedAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_attributes", nullable = false)
	private Map<String, Object> rawAttributes;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime createdAt;
}
