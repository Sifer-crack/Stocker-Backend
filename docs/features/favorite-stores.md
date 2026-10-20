# Favorite stores

User story: save favorite stores so specific items from them are easy to find.

| Step | Call (through the gateway, JWT required) | Owner |
|---|---|---|
| Save a store as favorite | `POST /api/shopping/favorite-stores` `{"chainId":"paknsave","storeId":null}` -> 201 | shopping |
| Favorites section | `GET /api/shopping/favorite-stores` | shopping |
| Remove | `DELETE /api/shopping/favorite-stores/{id}` -> 204 | shopping |
| View a store's items | `GET /api/pricing/stores/{chainId}/products?query=&limit=` | gateway -> pricing (gRPC `ChainCatalog.ListProducts`) |
| Add an item to the shopping list | `POST /api/shopping/items` (existing) | shopping |

- A favorite is a chain (`newworld`, `paknsave`, `woolworths`), optionally narrowed to one `storeId`. Saving the same
  one again returns the existing favorite. Per user: the id comes from `X-User-Id` (set by the gateway from the JWT).
- Store listing is **database only** (the latest stored price per product, rows newer than
  `app.pricing.match.max-age`); it never makes a supermarket request. `query` keeps matching products best first,
  `limit` defaults to 50 (max 200). Unknown chain: 422 `{"error": ...}`.
- Schema: shopping Flyway `V4__create_favorite_stores.sql` (unique per user + chain + store, NULL store treated as "").
- Contract copies to keep in sync by hand: `services/pricing/src/main/proto/chain_catalog.proto` and
  `gateway/src/main/proto/chain_catalog.proto`. The frontend's types (`../Stocker-Frontend/src/pricing/types.ts`)
  mirror the JSON by hand: `{products: [{itemId, name, brand|null, priceAmount, currency, promoFlag, packSize|null, capturedAt|null}]}`.
- Known duplication: `ChainProductService` repeats `ItemMatchService`'s name/pack-size rules (private there); fold into
  `StoreProductText` once the normalised-products work is merged.

Tests: see `docs/testing.md`. Database test: `STOCKER_SHOPPING_DB_IT=true` with `STOCKER_DB_URL` at a throwaway Postgres fixture (runtime uses the Neon `shopping` database).
