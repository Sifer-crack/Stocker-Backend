package com.stocker.pricing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "unmatched_search_terms")
@Getter
@Setter
@NoArgsConstructor
public class UnmatchedSearchTerm {

	@Id
	@Column(name = "normalized_term")
	private String normalizedTerm;

	@Column(name = "hit_count", nullable = false)
	private int hitCount;

	@Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime firstSeenAt;

	@Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
	private OffsetDateTime lastSeenAt;
}
