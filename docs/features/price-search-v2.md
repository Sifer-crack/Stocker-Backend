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
  "status": "found",
  "stale": false,
  "refresh": {},
  "products": [ Product ],
  "alternatives": [ Product ]
}
```

`status` and `refresh` are new (2026-10-20, additive; the other fields are unchanged). See "Status and refresh progress" below.

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
- Empty `products` and `alternatives` is not an error: read `status` (`refreshing` = a page load is still running, ask again; `not-found` = finished, nothing at any chain).
- `stale: true` = some price is older than the freshness
  threshold; a refresh may be running in the background (ask again shortly). The request never waits on a supermarket.
- Errors: 422 `{"error": "..."}` for blank `term`/`itemId`; 401 without a JWT.

## Choosing a product for the shopping list (so compare knows which one)

The first product in `products` is stored under `itemId` automatically (that is what `POST /api/pricing/compare` prices, one price
per chain). If the user picks a different product (from `products` or `alternatives`):

`PUT /api/pricing/items/{itemId}/product`  body `{"productKey": "..."}` -> 200 `{"itemId": "...", "productKey": "...", "chains": ["paknsave", "woolworths"]}`;
404 `{"error": ...}` when the key is not currently known. After this, compare prices that product.

## Status and refresh progress (the UI can stop saying "we'll keep looking")

Every `GET /api/pricing/products` answer carries a terminal-or-not `status`:

| `status` | Meaning | UI |
|---|---|---|
| `found` | `products` is not empty (prices may still be updating, see `refresh`) | show the products |
| `alternatives-only` | `products` is empty, `alternatives` is not, and no refresh is running | "No exact match - similar products:" |
| `refreshing` | `products` is empty and at least one chain's page load is still `pending` (`alternatives` may already hold generic matches) | spinner, ask again in 2-3 s |
| `not-found` | `products` and `alternatives` are empty and nothing is pending: the search is finished | "Nothing found at any supermarket" (stop polling) |

`refresh` is a map of lower-case chain id -> `pending` | `done` | `failed` | `skipped`, for the chains whose category page
was considered for this product or term in the last 30 minutes (empty object = no refresh known):

- `pending`: the page load is queued or running. `done`: it finished (the stored data now includes it). `failed`: it threw.
  `skipped`: no load was started (30-minute per-page cooldown, hourly cap, refresh disabled, request cancelled, or no known page).
- It is read from memory: asking again never starts a new supermarket request while a page is pending or within its cooldown.
  When a load finishes, the in-memory product copy is reloaded first, so `done` means the next answer already contains its prices.
- A request never waits on a supermarket (deadline 0): the answer is what is stored right now.

Example, a term nobody has stored yet, first ask then re-ask:

```json
{ "query": {"term": "quinoa flakes", "words": ["quinoa", "flake"], "size": null, "packCount": null, "grade": null, "category": null},
  "matchMethod": "lexical", "status": "refreshing", "stale": false,
  "refresh": {"newworld": "pending", "paknsave": "pending", "woolworths": "skipped"},
  "products": [], "alternatives": [] }
```
```json
{ "...": "...", "matchMethod": "lexical", "status": "not-found", "stale": false,
  "refresh": {"newworld": "done", "paknsave": "failed", "woolworths": "skipped"}, "products": [], "alternatives": [] }
```

## Generic fallback (`matchMethod: "generic-fallback"`)

When no stored product matches all words of the term (typically an unknown brand or a size nobody sells), the search no longer
comes back empty if products of the same TYPE exist. It drops the words no stored product has (a brand such as
"shaoxingshiye"), the size and pack-count qualifiers, then - if still nothing - the leading modifier words one by one, but never
below the last two words (so "cooking wine" stays together). The products that contain all the words left are returned as
`alternatives` (at most 5, never as `products`) with `matchMethod: "generic-fallback"` and `status: "alternatives-only"`.
Dishes and condiments made with the type are never offered ("Rice Wine Vinegar" is not wine; a dish/condiment word after the last
type word excludes the product). `query.size` still shows what the shopper asked for, so the UI can say "no 2.5L; here is what exists".

```json
{ "query": {"term": "shaoxingshiye cooking wine 2.5L", "words": ["shaoxingshiye", "cooking", "wine"], "size": "2.5L", "packCount": null, "grade": null, "category": "cooking-wine"},
  "matchMethod": "generic-fallback", "status": "alternatives-only", "stale": false, "refresh": {},
  "products": [],
  "alternatives": [ {"productKey": "double-pagoda-shao-xing-cooking-wine|640ml", "name": "Double Pagoda Shao Xing Cooking Wine 640ml", "packSize": "640ml", "...": "..."},
                    {"productKey": "shiwan-cooking-rice-wine|560ml", "name": "Shiwan Cooking Rice Wine 560ml", "...": "..."} ] }
```

A common product (`canonical`) whose only stored sizes differ from the requested one already returned those as `alternatives` with
`matchMethod: "canonical"`; that is unchanged (now with `status: "alternatives-only"`).

`cooking-wine` (common-products.txt) now also answers to "shao xing", "shaoxing", "rice wine" and "mirin" and excludes "vinegar".

## Speed (B8)

A warm request reads only the in-memory snapshot of the stored products (no database, no supermarket). Measured by
`ProductSearchPerformanceTest` on a 5,000-product fixture (3 chains, best of 5 warm runs, shared dev machine): common product
about 10-12 ms, lexical term about 11-16 ms, generic fallback about 16-18 ms, unknown term about 16-18 ms; the very first call
(cold word cache and JIT) about 34 ms. Product names are normalised once and remembered (`ProductNormalizer`), which took the
unknown-term path from 165 ms to under 30 ms. The snapshot itself reloads in the background (`app.pricing.snapshot.*`).

## Replacing the chosen product

`PUT /api/pricing/items/{itemId}/product` now REPLACES what was stored under `itemId`: the earlier product's price records and
`price_stats` for that item id are deleted (only the copies stored under the shopping-item id, never the scraped chain rows), then the
chosen product's newest row per chain is stored. Compare therefore prices the new product, also at a chain where the earlier product
had a more recent observation. Selecting what is already stored changes nothing. The automatic store of the first search result still
only adds (an item re-searched with a different term keeps the older stored rows until the shopper selects a product).

## Cancellation

If the client aborts the request (closes the connection / aborts `fetch`), the server stops further work for it, including
pending on-demand supermarket page loads (see `docs/features/price-engine-reliability.md`).

## Status

Matching, size and grade parsing: branch `feature/search-relevance-match`. Cancellation: `feature/search-relevance-cancel`.
Merged product model, `GET /api/pricing/products` and `PUT /api/pricing/items/{itemId}/product`: implemented on
`feature/search-relevance-v2` (pricing `ProductSearch` gRPC service in `price_search.proto`, `service/search/ProductSearchService`;
gateway `ProductSearchController`). Status, refresh progress, generic fallback and replace-on-select: `feature/search-coverage-v3`. Not pushed or merged yet. The frontend still uses `/match` (unchanged, deprecated).

Details the contract above leaves open:
- `query.size` is the size label (`"2L"`, `"500g"`), `query.packCount` an integer (`12`), `query.grade` a string (`"7"`); `query.category` is the common
  product's key (`"eggs"`, `"tim-tams"`) when the term is a common product, else the `category` hint, else null. `matchMethod` is `canonical` or `lexical`.
- `productKey` = `<brand+name words>|size-<grade>|<quantity><unit>` (e.g. `anchor-blue-top-milk|2000ml`, `pams-free-range-eggs|size-7|12each`).
  Listings merge into one product on the SET of normalised words (order, plurals, "fresh" and the way a size is written do not matter) plus grade
  and pack size, so a key stays valid in another word order. An extra descriptive word one chain adds (e.g. "Biscuits") makes a different product.
- Common products: the matches are the indexed rows of that product; alternatives also come from sibling products on the same shelf
  (same sitemap keyword, e.g. other biscuits for Tim Tam). Other terms use word overlap over everything stored.
- `PUT .../product` replaces what was stored under the item id (see "Replacing the chosen product").
- Shopping's Kafka backfill (`PriceRecordCaptured`, newer-observation-only merge) can still keep a newer price of the earlier product on the shopping item itself; compare (pricing) is correct.

## Frontend notes (section 4b of docs/frontend/FRONTEND_PROMPT.md should say the same)

1. Render by `status`, not by "lists are empty": `found` (show), `alternatives-only` (show the alternatives with a "no exact match" line), `refreshing` (spinner and ask again every 2-3 s, at most about 10 times), `not-found` (final, stop).
2. Re-asking is cheap and safe: it starts no new supermarket request while a page is pending or cooling down.
3. `refresh` may be shown as per-chain chips (`pending` spinner, `done` tick, `failed` warning, `skipped` grey).
4. `matchMethod: "generic-fallback"` means the products are of the same type, not the product the shopper typed; label them accordingly.
5. After the shopper picks a product, `PUT /api/pricing/items/{itemId}/product` makes compare use exactly that product.

## Search-bar suggestions: `GET /api/pricing/suggest`  (JWT required, through the gateway)

Why: the shopping-list search box used `GET /api/products/search` (catalog service, 765 niche rows such as "gao yue long shan
cooking wine 0.6 L", only 3 egg products), so shoppers picked items most NZ supermarkets do not stock and got "no exact match".
Suggestions now come from what the chains actually sell: the stored `normalized_products` rows, read from the in-memory
`NormalizedProductSnapshot` (no database when warm, **never a supermarket request**). Branch `feature/search-suggest`; additive.

Query parameters: `q` (what has been typed; optional), `limit` (optional, default 8, max 20).

```json
{
  "q": "wine",
  "suggestions": [
    { "text": "Wine",         "canonicalKey": "wine",         "chainCount": 3, "chains": ["newworld", "paknsave", "woolworths"], "sampleSize": "750ml", "productCount": 41 },
    { "text": "Cooking wine", "canonicalKey": "cooking-wine", "chainCount": 3, "chains": ["newworld", "paknsave", "woolworths"], "sampleSize": "640ml", "productCount": 9 },
    { "text": "Red wine",     "canonicalKey": "wine",         "chainCount": 3, "chains": ["newworld", "paknsave", "woolworths"], "sampleSize": "750ml", "productCount": 18 },
    { "text": "White wine",   "canonicalKey": "wine",         "chainCount": 2, "chains": ["newworld", "woolworths"],             "sampleSize": "750ml", "productCount": 12 },
    { "text": "Church Road Chardonnay", "canonicalKey": null, "chainCount": 1, "chains": ["woolworths"], "sampleSize": "750ml", "productCount": 1 }
  ]
}
```

(The numbers are illustrative; the shape is the contract.) Fields of each suggestion:

- `text`: clean display name without size, grade or pack count ("Cooking wine", "Pams Free Range Eggs").
- `canonicalKey`: the common-product key, or `null` for a specific product that has none. Several key-level suggestions can share a key
  (`Wine`, `Red wine`, `White wine` are all `wine`).
- `chainCount` 1-3 and `chains` (lower-case ids, sorted): how many chains carry a matching product.
- `sampleSize`: the most common pack size behind the suggestion (`"640ml"`, `"12 pack"`), or `null`.
- `productCount`: distinct products (name + size) behind it.

Behaviour:

- `q` is split into words. Every word except the last must be a whole word of the product (a plural is fine: "eggs" finds "egg");
  the **last word is a prefix** (typing as you go): `eg` -> Eggs, `cooking wi` -> Cooking wine, `coo wine` -> nothing. A trailing number is ignored.
- **Empty or 1-character `q`** returns the most widely stocked common products (`chainCount` 3 first), so the box can show ideas before typing.
- Order: key-level generic suggestions first (the product name, then up to 2 aliases per key: `wine` -> Wine, Cooking wine, Red wine, White wine),
  at most 5 of them; then specific products (brand + type), the rows of a key already offered are folded into that key's suggestion
  (one "Eggs", not 30 egg rows). Within each group: `chainCount` desc (all three chains first), then `productCount` desc, then the shorter text.
- Distinct texts (case-insensitive). Never an error for odd input: unknown text gives `{"q": "...", "suggestions": []}`.
- Only products seen within `app.pricing.match.canonical-max-age` (90 days) with a positive price count.
- Errors: 401 without a JWT. gRPC: `ProductSearch.SuggestProducts` (`price_search.proto`, identical copies in pricing and gateway).

Speed: `ProductSuggestPerformanceTest` on a 5,000-product fixture (3 chains): best of 5 warm runs about 5-8 ms for an empty, prefix, two-word,
brand-prefix and no-match query (bound in the test: 100 ms); the very first call (cold word cache and JIT) about 0.2-0.3 s.
Cleaned names are remembered per stored name, so a keystroke is a loop over the snapshot with set lookups.

### Frontend notes (suggestions)

1. Use `GET /api/pricing/suggest?q=<typed text>&limit=8` for the shopping-list search box instead of `/api/products/search`
   (debounce about 150-200 ms and abort the previous request; it is cheap and memory-only).
2. Show `text`, and a small "at 2 of 3 chains" hint from `chainCount` / `chains` (e.g. chain chips); `sampleSize` can be shown muted next to it.
3. When the shopper picks a suggestion, call `GET /api/pricing/products?term=<text>&itemId=<id>` (the term is the suggestion `text`; for a
   key-level suggestion this is a common product and answers `canonical`). Then continue with the status handling described above.
4. Keep `/api/products/search` only for the pantry catalog lookup, if the pantry still needs it; it must not feed the shopping list.
5. An empty `suggestions` list is normal (nothing stored that matches): let the shopper search the typed text as is; `/api/pricing/products`
   then reports `refreshing` or `not-found` as before.
