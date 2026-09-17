-- Flyway migration: V2__create_sitemap_category_cache.sql
-- Service: pricing (com.stocker.pricing)
-- Persisted (L2) cache of each chain's sitemap-derived category URLs, backing the ingest/
-- module's SitemapCategoryDiscoveryService. Aggressively cached (long TTL, see
-- app.ingest.sitemap-cache-ttl-days) so category discovery doesn't mean re-downloading and
-- re-parsing a chain's full sitemap on every lookup.

CREATE TABLE sitemap_category_cache (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_id        TEXT NOT NULL,
    category_urls   JSONB NOT NULL DEFAULT '[]'::JSONB,
    fetched_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_sitemap_category_cache_chain_id UNIQUE (chain_id)
);

COMMENT ON TABLE sitemap_category_cache IS
    'Two-tier (in-memory + this table) cache of category-page URLs discovered from each chain''s own sitemap XML, keyed by chain_id, refreshed on a long TTL.';
