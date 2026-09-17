package com.stocker.pricing.ingest.sitemap.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** L2 (Postgres) tier of the sitemap category-URL cache. See SitemapCategoryDiscoveryService. */
@Entity
@Table(name = "sitemap_category_cache")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SitemapCategoryCacheEntry {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "chain_id", nullable = false, unique = true)
	private String chainId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "category_urls", nullable = false)
	private List<String> categoryUrls;

	@Column(name = "fetched_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime fetchedAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime updatedAt;
}
