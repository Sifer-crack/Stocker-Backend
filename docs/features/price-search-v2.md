# Price search v2: one product, a price per chain, and real alternatives (contract)

Owner's findings (manual testing, 2026-10-19): alternatives were the same product at another chain; "Milk 2L" and "Milk"
gave identical results; eggs and Tim Tams were not found; a cancelled search kept working. Decision: **prices are
tracked per CHAIN, not per store** (no branch/store/distance/travel features).

This is **additive**: the existing `GET /api/pricing/match` keeps working unchanged (deprecated) until the frontend has
moved to the new endpoint below.

## `GET /api/pricing/products`  (JWT required, through the gateway)

Query parameters: `term` (required), `itemId` (required: the shopping-list item the search belongs to),
`category` (optional hint), `limit` (optional, default 10, max 30 products; alternatives are capped separately at 5).
Size and quantity qualifiers are read from `term`: `2L`, `1.5 litres`, `500g`, `12 pack`, `12pk`, `x12`, `dozen`,
egg grades `size 7`. They are NOT query parameters.

```json
{
  "query": { "term": "eggs size 7", "words": ["eggs"], "size": null, "packCount": null, "grade": "7", "category": "eggs" },
  "matchMethod": "canonical",
  "stale": false,
  "products": [ Product ],
  "alternatives": [ Product ]
}
```

`Product` (the same product from several chains is ONE entry):

```json
{
  "productKey": "pams-free-range-eggs|size-7|12each",
  "name": "Pams Free Range Eggs Size 7 12 Pack",
  "brand": "Pams",
  "category": "eggs",
  "packSize": "12 pack",
  "grade": "7",
  "unit": "each",
  "unitPrice": 0.45,
  "cheapestChainId": "paknsave",
  "prices": {
    "paknsave":   { "price": 5.40, "unitPrice": 0.45, "promo": false, "storeId": "paknsave:",   "capturedAt": "2026-10-19T14:00:00Z", "stale": false },
    "woolworths": { "price": 5.90, "unitPrice": 0.49, "promo": true,  "storeId": "woolworths:", "capturedAt": "2026-10-19T14:00:00Z", "stale": false }
  }
}
```

- `prices` keys are **lower-case chain ids** (`newworld`, `paknsave`, `woolworths`), the same casing as compare, shopping and
  favorites (this removes the UPPER-case `/match` inconsistency). A chain missing from `prices` does not carry the product (or has no data yet).
- `unit` is `each`, `l` or `kg`; `unitPrice` is the cheapest chain's price per unit, so different sizes can be compared.
  `brand`, `packSize`, `grade`, `unit`, `unitPrice` may be `null`.
- `products` = the matches, best first: relevance, then size/grade fit, then unit price. With a size qualifier in the term only
  products of that size are matches; without one all sizes are matches, ordered by relevance then unit price.
- `alternatives` = genuinely **different** products of the same category (other brand, size or variant). Never a product that is already in
  `products`; never the same product at another chain. At most 5, ranked by relevance and closeness in price.
  If nothing of the requested size exists, `products` is empty and the other sizes are the alternatives.
- Empty `products` and `alternatives` means "nothing stored yet", not an error. `stale: true` = some price is older than the freshness
  threshold; a refresh may be running in the background (ask again shortly). The request never waits on a supermarket.
- Errors: 422 `{"error": "..."}` for blank `term`/`itemId`; 401 without a JWT.

## Choosing a product for the shopping list (so compare knows which one)

The first product in `products` is stored under `itemId` automatically (that is what `POST /api/pricing/compare` prices, one price
per chain). If the user picks a different product (from `products` or `alternatives`):

`PUT /api/pricing/items/{itemId}/product`  body `{"productKey": "..."}` -> 200 `{"itemId": "...", "productKey": "...", "chains": ["paknsave", "woolworths"]}`;
404 `{"error": ...}` when the key is not currently known. After this, compare prices that product.

## Cancellation

If the client aborts the request (closes the connection / aborts `fetch`), the server stops further work for it, including
pending on-demand supermarket page loads (see `docs/features/price-engine-reliability.md`).

## Status

Matching, size and grade parsing: branch `feature/search-relevance-match`. Cancellation: `feature/search-relevance-cancel`.
Merged product model, `GET /api/pricing/products` and `PUT /api/pricing/items/{itemId}/product`: implemented on
`feature/search-relevance-v2` (pricing `ProductSearch` gRPC service in `price_search.proto`, `service/search/ProductSearchService`;
gateway `ProductSearchController`). Not pushed or merged yet. The frontend still uses `/match` (unchanged, deprecated).

Details the contract above leaves open:
- `query.size` is the size label (`"2L"`, `"500g"`), `query.packCount` an integer (`12`), `query.grade` a string (`"7"`); `query.category` is the common
  product's key (`"eggs"`, `"tim-tams"`) when the term is a common product, else the `category` hint, else null. `matchMethod` is `canonical` or `lexical`.
- `productKey` = `<brand+name words>|size-<grade>|<quantity><unit>` (e.g. `anchor-blue-top-milk|2000ml`, `pams-free-range-eggs|size-7|12each`).
  Listings merge into one product on the SET of normalised words (order, plurals, "fresh" and the way a size is written do not matter) plus grade
  and pack size, so a key stays valid in another word order. An extra descriptive word one chain adds (e.g. "Biscuits") makes a different product.
- Common products: the matches are the indexed rows of that product; alternatives also come from sibling products on the same shelf
  (same sitemap keyword, e.g. other biscuits for Tim Tam). Other terms use word overlap over everything stored.
- `PUT .../product` stores the chosen product's newest row per chain under the item id; it does not remove what was stored before (see below).
- Known limitation: compare reads `price_stats` (cheapest row per chain and item, newest observation wins per store), so after a PUT a chain whose
  earlier stored product was observed more recently than the chosen one keeps the earlier price until a newer observation arrives.
