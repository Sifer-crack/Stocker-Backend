package com.stocker.pricing.ingest.sitemap;

import com.stocker.pricing.ingest.sitemap.model.SitemapCategoryCacheEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SitemapCategoryCacheRepository extends JpaRepository<SitemapCategoryCacheEntry, UUID> {

	Optional<SitemapCategoryCacheEntry> findByChainId(String chainId);
}
