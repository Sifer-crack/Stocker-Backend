package com.stocker.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-(itemId, storeId) price summary derived from price_records - see PriceStatsService, which
 * owns recomputing this whenever a new price_records row is written. priceHistory entries are
 * plain Map&lt;String,Object&gt; (not a typed record) with capturedAt pre-formatted as an
 * ISO-8601 string, mirroring PriceRecord.rawAttributes' proven JSONB mapping - deliberately
 * avoids storing a raw OffsetDateTime inside the JSON-mapped field, since Hibernate's default
 * JSON FormatMapper isn't guaranteed to have the JSR310 module registered.
 */
@Entity
@Table(name = "price_stats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceStats {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "item_id", nullable = false)
	private String itemId;

	@Column(name = "store_id", nullable = false)
	private String storeId;

	@Column(name = "chain_id", nullable = false)
	private String chainId;

	@Column(name = "currency", nullable = false)
	private String currency;

	@Column(name = "current_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal currentPrice;

	@Column(name = "lowest_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal lowestPrice;

	@Column(name = "highest_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal highestPrice;

	@Column(name = "median_price", nullable = false, precision = 10, scale = 2)
	private BigDecimal medianPrice;

	@Column(name = "current_promo_flag", nullable = false)
	private boolean currentPromoFlag;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "price_history", nullable = false)
	private List<Map<String, Object>> priceHistory;

	@Column(name = "observation_count", nullable = false)
	private int observationCount;

	@Column(name = "first_observed_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime firstObservedAt;

	@Column(name = "last_observed_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime lastObservedAt;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime updatedAt;
}
