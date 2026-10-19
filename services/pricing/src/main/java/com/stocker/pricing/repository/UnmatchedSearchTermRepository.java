package com.stocker.pricing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.stocker.pricing.model.UnmatchedSearchTerm;

public interface UnmatchedSearchTermRepository extends JpaRepository<UnmatchedSearchTerm, String> {

	@Modifying
	@Query(value = """
			insert into unmatched_search_terms (normalized_term) values (:term)
			on conflict (normalized_term) do update
			set hit_count = unmatched_search_terms.hit_count + 1, last_seen_at = now()
			""", nativeQuery = true)
	void record(@Param("term") String term);
}
