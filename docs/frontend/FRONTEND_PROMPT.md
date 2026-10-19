# Prompt for the Stocker FRONTEND session (Sprint 2 features)

Paste everything below the line into a Claude Code session opened in `Stocker-Frontend`
(React 19 + Vite 8 + TypeScript 6 SPA). Written against backend branch `integration/sprint2-manual-test`
(all Sprint 2 features merged); every endpoint, header and JSON field below was checked against that code.

---

You are working in the Stocker **frontend** repo (sibling of `Stocker-Backend`). Your job: finish and verify the
UI for four Sprint 2 backend features (expiry notifications, favorite stores, shopping list + price comparison +
savings, delivery / click-and-collect costs) and move the last pages off direct service calls.

**Read first:** `CLAUDE.md`, `AGENTS.md`, `src/pricing/README.md`, then the files named in section 0.

## 0. What already exists (do not rebuild it; verify and extend)

The branch `feature/frontend-backend-sync` already contains most of the wiring. Audit it against this document
and fix differences rather than rewriting:

| Area | Existing code | State |
|---|---|---|
| Auth | `src/context/AuthContext.tsx`, `src/lib/authFetch.ts` | works, but **never refreshes the access token** (see 1.3) |
| Shopping list + SSE | `src/context/ShoppingListContext.tsx`, `src/lib/sseStream.ts`, `src/pricing/shoppingApi.ts` | done; fetch-based SSE |
| Match | `src/pricing/api.ts` `matchItem`, `matchPolling.ts` | done |
| Favorites | `src/pages/FavoriteStores.tsx`, `src/pricing/storesApi.ts` | done; verify against section 3 |
| Notifications | `src/context/NotificationsContext.tsx`, `src/pages/Notifications.tsx`, `src/lib/notificationsApi.ts` | done (60 s poll, sidebar badge) |
| Pantry expiry fields | `src/pages/Pantry.tsx` | UI done, **but calls `http://localhost:8087` and `:8082` directly** |
| Dashboard | `src/pages/Dashboard.tsx` | **calls `http://localhost:8087` directly** |
| Compare | `src/pricing/ShoppingListCompare.tsx`, `api.ts` `compareShoppingList` | works for totals; **no fulfilment support**, and has a savings-baseline bug (see 4.4) |

Gaps to close: (1) gateway-only pantry/catalog calls, (2) token refresh, (3) fulfilment costs UI + types,
(4) `selectedChainId` casing bug, (5) acceptance-criteria verification of each feature, (6) tests/build.

## 1. Context and rules

### 1.1 Talk ONLY to the gateway
- Base URL: `API_BASE` from `src/lib/apiConfig.ts` (default `http://localhost:8080`). Never hardcode another URL.
  Never call `:8081-:8087` (identity, catalog, shopping, pricing, notifications, analytics, inventory) directly.
- Dev server origin must be `http://localhost:5173` (the gateway's CORS default `STOCKER_CORS_ALLOWED_ORIGINS`
  = `http://localhost:3000,http://localhost:5173`). A different origin needs a backend env change, not a frontend one.
  CORS allows credentials, methods GET/POST/PUT/PATCH/DELETE/OPTIONS and all headers.
- Exceptions: `src/lib/nutrition.ts` (external NZ food-composition API) is intentionally not the gateway.

### 1.2 How auth works
1. `POST /api/identity/register` (no auth) body `{"email","password","firstName"?,"lastName"?,"groceryBudget"?}`
   (password 8-100 chars, valid email, budget >= 0) -> **201** `{"id","email","firstName","lastName","groceryBudget"}`.
   No token is returned; log in afterwards. Duplicate email -> **409** `{"error":"email already registered"}`.
2. `POST /api/identity/login` `{"email","password"}` -> **200**
   `{"accessToken":"<jwt>","tokenType":"Bearer","expiresIn":900}` and a `Set-Cookie: refreshToken=...; HttpOnly;
   SameSite=Lax; Path=/; Max-Age=604800` (Secure is off in dev). Wrong credentials -> **401** `{"error":"invalid credentials"}`.
3. `POST /api/identity/refresh` (no body, cookie only, so `credentials: 'include'`) -> same body as login and a
   rotated cookie. No/invalid cookie -> **401** `{"error":"invalid credentials"}`.
4. `POST /api/identity/logout` -> **204**, clears the cookie.
5. Every other call: `Authorization: Bearer <accessToken>` (HS256 JWT, subject = user UUID, claims `email`,
   `type:"access"`, lifetime **900 s = 15 min**). Keep the token in memory only (current behaviour).
6. The gateway verifies the JWT, **strips** any client `X-User-Id` / `X-Household-Id` / `X-Roles`, and sets
   `X-User-Id` (= JWT subject) itself on proxied requests. **Never send these headers** and never send a user id for
   shopping, favorites or notifications: the backend derives it. Unauthenticated gateway answers: **401**
   `{"error":"unauthorized"}`; **403** `{"error":"forbidden"}`.
7. Public (no JWT): `GET /healthz`, actuator health/info, the 4 identity POSTs above, and `GET /api/products/**`
   (catalog reads). `POST/DELETE /api/products` need a JWT.
8. `GET /api/identity/me` -> `{"id","email","firstName","lastName","groceryBudget"}` (`groceryBudget` may be null;
   `firstName`/`lastName` may be null). `PATCH /api/identity/me` `{"groceryBudget": number|null}` (>= 0).
   `UserContext` already loads this.
9. Error body convention: gateway/identity/shopping/pricing-BFF errors are `{"error":"<message>"}`. **Inventory and
   notifications 400/404 use `{"message":"..."}`** (see 2.4). Make your error reader accept both
   (`error` first, then `message`) and fall back to `Request failed with status N`.

### 1.3 Required fix: silent token refresh
The access token lives 15 min and nothing refreshes it today, so after 15 min every call, the notification poll and the
SSE stream get 401 and stay broken until reload. Implement in `AuthContext`/`authFetch`:
- Schedule `POST /api/identity/refresh` at about `expiresIn - 60` s after each login/refresh (store `expiresIn`;
  `AuthContext` currently discards it).
- Also: on a 401 from `authFetch`, refresh once and retry the request once; if refresh fails, log out.
- `sseStream.ts` already re-reads the token on reconnect and waits on 401 until the token changes: keep that, it
  becomes correct once the token rotates.

### 1.4 Pantry bypasses the gateway: what to change
`Pantry.tsx` and `Dashboard.tsx` hardcode these; replace with `authFetch(`${API_BASE}...`)`:

| Today (direct) | Gateway path | Auth |
|---|---|---|
| `GET http://localhost:8087/pantry-items?userId=` | `GET /api/pantry-items?userId=<uuid>` | JWT |
| `POST http://localhost:8087/pantry-items?...` | `POST /api/pantry-items?userId=&productId=&quantity=[&expirationDate=&notifyDaysBefore=]` | JWT |
| `PUT .../pantry-items/{id}?quantity=` | `PUT /api/pantry-items/{id}?quantity=` | JWT |
| `PUT .../pantry-items/{id}/expiry?...` | `PUT /api/pantry-items/{id}/expiry?[expirationDate=&notifyDaysBefore=]` | JWT |
| `DELETE .../pantry-items/{id}` | `DELETE /api/pantry-items/{id}` | JWT |
| `GET http://localhost:8082/products/{id}` | `GET /api/products/{id}` | public |
| `GET http://localhost:8082/products/search?query=` | `GET /api/products/search?query=` | public (send JWT anyway) |

The gateway route does `StripPrefix=1` (`/api/pantry-items/x` -> inventory `/pantry-items/x`), so query strings pass through unchanged.

**Backend limitation to show in code comments:** inventory takes `userId` as a **request parameter** and ignores
`X-User-Id`. Get the id from `GET /api/identity/me` (`useUser().user.id`, already done in `Pantry.tsx`). Consequences
(not for you to fix, but never rely on them): any logged-in user could pass another user's id, and `PUT`/`DELETE`
by item id are not ownership-checked. The frontend must only ever send the current user's id.

### 1.5 Conventions to keep (from CLAUDE.md / AGENTS.md)
- `import type` for types; no enums or parameter properties (`erasableSyntaxOnly`); strict unused checks.
- Plain CSS file per page/component (`Page.css`), no UI library, light theme only (palette: text `#173022`, accent
  `#198754`, border `#dfe5dc`/`#d4ddd1`, muted `#879488`/`#718075`, page bg `#f7f8f5`). There is **no dark mode and no
  theme system on this branch**: do not introduce one. Reuse existing classes (`shopping-page`, `shopping-list-card`,
  `pricing-card`, `pricing-table`, `pricing-error`, `empty-list`, `nav-badge`).
- Sidebar items live in `src/components/Sidebar.tsx`; routes in `src/App.tsx` (everything but `/`, `/login`,
  `/signup` is under `ProtectedRoute`).
- Show product names, never item ids. Use `newId()` from `src/lib/uuid.ts`, not `crypto.randomUUID()`.
- Every list/screen needs loading, empty and error states. Errors show the backend message.

## 2. Feature (a): Expiry notifications

**User story:** As a user I set an expiration date and "notify me N days before" on pantry items, and I get a
notification (list + badge) when the item is inside its window; I can acknowledge it; no duplicates.

### 2.1 UI
- Pantry add form and edit row: optional date input (`<input type="date">`, value `yyyy-mm-dd`) + number input
  "Remind me N days before" (integer >= 0, default hint "3"). Disable/clear the number when no date is set.
- Pantry list row: "Expires 21/10/2026" (and "(reminder 3 days before)" when set).
- Sidebar "Notifications" with unread badge; `/notifications` page: unread first, product name, "Expires in N days /
  today / Expired N days ago", button "Got it" -> acknowledge; acknowledged rows dimmed.
- States: loading, empty ("Nothing is about to expire..."), error banner, per-row acknowledge error.

### 2.2 Endpoints (all via the gateway, JWT required)

| Action | Request | Success | Errors |
|---|---|---|---|
| Add pantry item with expiry | `POST /api/pantry-items?userId=&productId=&quantity=&expirationDate=2026-10-26&notifyDaysBefore=3` (no body) | **200** pantry item JSON | 400 `{"message"}` |
| Set / clear expiry | `PUT /api/pantry-items/{id}/expiry?expirationDate=2026-10-21&notifyDaysBefore=1` (omit both = clear) | **200** pantry item JSON | 400 `{"message":"Pantry item not found"}` (unknown id is a 400, not 404) |
| List notifications | `GET /api/notifications` | **200** array, newest first | 401 |
| Acknowledge | `POST /api/notifications/{id}/acknowledge` (no body) | **200** the notification, `status:"ACKNOWLEDGED"` | 404 `{"message": "..."}` (unknown or someone else's) |

Details verified in code:
- `expirationDate` must be ISO `yyyy-mm-dd`. `notifyDaysBefore` is an integer; **negative -> 400**
  `{"message":"Notification threshold cannot be negative"}`; **a threshold without a date -> 400**
  `{"message":"A notification threshold needs an expiration date"}`. `quantity < 1` -> 400
  `{"message":"Quantity must be at least 1"}`.
- Adding a product that is already in the pantry **adds the quantity** to the existing row (same
  `pantry_item_id`); a new date replaces the old date and threshold; no date given keeps what was there.
- `PUT /api/pantry-items/{id}?quantity=` changes only quantity (>= 1). Sending the expiry PUT with no params **clears**
  both fields (`Pantry.tsx` relies on this when saving an edit).
- `DELETE /api/pantry-items/{id}` returns 200 with an empty body (do not `.json()` it).

**Pantry item JSON (mixed casing, verified in `PantryItem.java` getters + controller test):**
```json
{
  "pantry_item_id": "0b6f...-uuid",
  "user_id": "550e...-uuid",
  "product_id": "9a1c...-uuid",
  "quantity": 2,
  "expirationDate": "2026-10-26",
  "notifyDaysBefore": 3
}
```
`pantry_item_id`, `user_id`, `product_id` are **snake_case**; `quantity`, `expirationDate`, `notifyDaysBefore` are
camelCase. `expirationDate` / `notifyDaysBefore` are `null` (or absent; treat both the same) when not set.

**Notification JSON (`NotificationResponse`):**
```json
{
  "id": "uuid",
  "pantryItemId": "uuid",
  "productId": "uuid",
  "expirationDate": "2026-10-21",
  "status": "NEW",
  "createdAt": "2026-10-19T01:00:00Z",
  "acknowledgedAt": null
}
```
`status` is `"NEW" | "ACKNOWLEDGED"` (upper case). `acknowledgedAt` is null until acknowledged. There is **no product
name and no message text**: resolve the name with `GET /api/products/{productId}` -> `{"productId","productName",
"groceryType","sellingWeightKg","sellingVolumeL"}` (`NotificationsContext` already does; keep a cache). The list contains
acknowledged rows too (history), so the badge counts `status === 'NEW'` only. Acknowledging twice is safe.

### 2.3 Polling guidance
No push exists. A scheduler in notifications scans pantry items every **15 minutes** (first scan 30 s after start;
zone Pacific/Auckland), creating at most one notification per (pantry item, expiration date). Threshold rule: a
notification exists once `today >= expirationDate - notifyDaysBefore` (default **3 days** when the threshold is null)
and stays due after expiry. So polling faster than 1 minute is pointless: keep **60 s while the tab is visible, plus a
fetch on tab focus and right after a pantry add/expiry edit** (as implemented). For a quick manual check, set a date
within the window and wait up to 15 min (see `docs/MANUAL_TESTING.md` for the faster procedure).
Changing an item's date later creates a **new** notification for the new date (the old one remains).

### 2.4 Types (add/keep)
See section 7 (`PantryApiItem`, `ExpiryNotification`).

### 2.5 Acceptance criteria (Trello) and how to verify in the UI
1. Expiration date is saved: add/edit item, reload, date still shown.
2. Notification threshold is saved: the "N days before" value persists after reload.
3. Notified inside the threshold: item expiring in <= N days appears in Notifications after the next scan.
4. Not notified outside the threshold: item expiring in > N days produces nothing.
5. Acknowledge: "Got it" turns the row to ACKNOWLEDGED and lowers the badge.
6. No duplicates: repeated polls / scans never show two rows for the same item+date; do not add client-side duplicates.
7. No date -> no notification (also after clearing a date).
8. Delivered on time: appears within one scan interval (15 min) of entering the window; the UI picks it up within 60 s
   of the notification existing.
**Definition of done:** all 8 verified manually against the running stack; no `localhost:8087/8082` left in `src/`;
errors from `{"message"}` bodies are shown; lint + build pass.

## 3. Feature (b): Favorite stores

**User story:** I save a supermarket as a favorite, see my favorites, open one to see its stored items, and add an item
to my shopping list.

### 3.1 UI
Favorites page (`/favorite-stores`): "Add a store" select (New World, PAK'nSAVE, Woolworths, minus those already
saved), list of favorites with remove button, selecting one shows a searchable product table (name, pack size, price,
promo tag, "captured <date>"), "Add to list" button per product. States: loading favorites, no favorites, loading
products, no products ("Nothing stored for this store yet"), errors.

### 3.2 Endpoints (gateway, JWT)

| Action | Request | Success | Errors |
|---|---|---|---|
| Save | `POST /api/shopping/favorite-stores` `{"chainId":"paknsave","storeId":null}` | **201** favorite (saving the same chain+store again returns the existing one, still 201) | 400 `{"error":"Unknown store chain 'x'; expected one of [newworld, paknsave, woolworths]"}`; 400 `{"error":"chainId: must not be blank"}` |
| List | `GET /api/shopping/favorite-stores` | **200** array | 401 |
| Remove | `DELETE /api/shopping/favorite-stores/{id}` | **204** no body | 404 `{"error":"..."}` (unknown or not yours) |
| Store items | `GET /api/pricing/stores/{chainId}/products?query=milk&limit=50` | **200** | 422 `{"error":"Unknown store chain 'countdown'"}` |
| Add to list | `POST /api/shopping/items` (see 4.2) | **201** | 400 |

```json
// favorite (FavoriteStoreResponse)
{ "id": "uuid", "chainId": "paknsave", "storeId": null, "createdAt": "2026-10-19T02:00:00+13:00" }
// GET /api/pricing/stores/paknsave/products?query=milk&limit=20
{ "products": [
  { "itemId": "paknsave:1", "name": "Anchor Milk 2l", "brand": "Anchor", "priceAmount": 4.8, "currency": "NZD",
    "promoFlag": true, "packSize": "2l", "capturedAt": "2026-10-19T02:00:00Z" },
  { "itemId": "paknsave:2", "name": "Pams Milk 1L", "brand": null, "priceAmount": 2.2, "currency": "NZD",
    "promoFlag": false, "packSize": null, "capturedAt": null } ] }
```
Rules verified in code: chain ids accepted case-insensitively and stored/returned **lower case** (`newworld`,
`paknsave`, `woolworths`); `limit` default 50, max 200 (0/absent = 50); `query` optional, results then ordered best
match first, otherwise alphabetical; data comes from the database only (never a live supermarket request) and only
rows newer than the match max-age window, so a store can legitimately return `[]`. `itemId` is an ingest id: never
show it. `capturedAt` is ISO UTC or null (`createdAt` of a favorite is an offset timestamp: parse with `new Date()`).
`storeId` is null today (no store picker exists); send `null`.
Adding a product to the list: `POST /api/shopping/items {"name": "<product name incl. pack size>", "quantity": 1}`
(the name is what pricing searches on).

### 3.3 Acceptance criteria
1. A favorite can be saved from the UI. 2. Favorites are listed after reload (per user: a second account sees none).
3. A favorite can be removed. 4. Selecting a favorite shows that store's items only. 5. Searching narrows the list.
6. An item can be added to the shopping list from a store's item list and appears (pending, then priced).
7. Saving the same store twice does not create a duplicate row (re-add returns the same `id`; update state by id).
**Definition of done:** the above verified; chain casing handled (section 6); lint + build pass.

## 4. Feature (c): Shopping list, price match, comparison, savings

### 4.1 Region
Supported regions (service area, `app.service-area.supported-regions`, matched **case-insensitively, trimmed**):
`auckland`, `wellington`, `christchurch`. They can be changed by backend env, so use a free-text input with these as
suggestions (as `ShoppingListCompare` does) and always show the server message on 422.

### 4.2 Add an item and receive prices
`POST /api/shopping/items` body `{"name": string (required, <=200), "sku"?: string, "category"?: string,
"region"?: string, "quantity"?: int 1..999 (default 1)}` -> **201** immediately, `comparisonStatus:"pending"`:
```json
{ "id": "uuid", "name": "full cream milk 2L", "sku": null, "category": "milk", "region": null, "quantity": 2,
  "comparisonStatus": "pending", "cheapest": null, "prices": [], "comparedAt": null,
  "createdAt": "2026-09-21T12:00:00Z" }
```
Errors: blank name -> **400** `{"error":"name: must not be blank"}` (field-prefixed, `; `-joined); malformed JSON ->
400 `{"error":"malformed request body"}`. `region`, if sent, must be supported or the item ends `unavailable`; omit it
when unknown (a blank region skips the check). There are **no** endpoints to delete/tick items: that stays in the
`localStorage` overlay.
`GET /api/shopping/items` (newest first) and `GET /api/shopping/items/{id}` (404 `{"error":"item not found"}`) return
the same shape. After the response, shopping calls pricing once (8 s deadline, no retry) in the background:
- `comparisonStatus`: `"pending"` (not priced yet, may stay so) | `"available"` | `"unavailable"` (pricing rejected,
  e.g. region outside the service area).
- When `available`: `cheapest` is a `PriceView`, `prices` lists every (chain, store) price ascending, `comparedAt` set.
- `PriceView`: `{"chainId":"paknsave","storeId":"ps:1","priceAmount":4.20,"currency":"NZD","promoFlag":false,
  "capturedAt":"..."|null}`; `priceAmount` is a decimal number; chain ids here are **lower case**.

### 4.3 Live updates: SSE with auth
`GET /api/updates/shopping`, `Accept: text/event-stream`. The gateway accepts the JWT **only in the `Authorization`
header** (no query-string token, no cookie auth), and the browser `EventSource` cannot set headers, so use a
fetch-based reader: `fetch(url, {headers:{Authorization:`Bearer ${t}`, Accept:'text/event-stream'}, signal})`, read
`response.body.getReader()`, split on blank lines, parse `event:` / `data:` fields. This is exactly
`src/lib/sseStream.ts`: keep it. Wire format:
```
: keep-alive                      <- comment heartbeat, immediately on open, then every 25 s (ignore)

event: shopping-item-updated
data: {"source":"sync","item":{ ...full item state, same shape as 4.2... }}
```
`source` is `"sync"` (immediate result) or `"backfill"` (later, from pricing events). The push is the **full** item:
replace by id, never merge. The owner's user id is not included. Behaviour to keep: refetch `GET /api/shopping/items`
on every (re)connect (pushes are best-effort, single gateway instance, nothing is replayed), reconnect with backoff,
treat 60 s of silence as dead (heartbeat is 25 s), on 401 wait for a refreshed token (needs section 1.3).

### 4.4 Match (per-chain detail)
`GET /api/pricing/match?term=<text>&itemId=<shopping item id>[&category=][&weightKg=][&volumeL=]` (JWT).
`term` and `itemId` required (blank -> **422** `{"error":"term and itemId are required"}`; missing param -> Spring 400).
The matched prices are stored **under `itemId`**, which is what makes `POST /api/pricing/compare` able to price that
item. Always pass the shopping item's id as `itemId`.
```json
{ "matchMethod": "canonical",
  "matches": [ { "chainId": "PAKNSAVE", "storeId": "paknsave:123", "productName": "Pams Milk 2L", "brand": null,
      "priceAmount": 3.9, "currency": "NZD", "promoFlag": false, "capturedAt": "2026-09-21T12:00:00Z",
      "score": 0.9, "productUrl": null, "matchType": "exact", "unitPrice": 1.95, "packSize": "2l" } ],
  "alternatives": [ { "...same shape...": "", "matchType": "alternative" } ],
  "stale": false }
```
- `matchMethod`: `"canonical"` (common product, normalised index) or `"lexical"` (word-overlap fallback).
- `matches`: at most one per chain, cheapest first, `matchType:"exact"`. `alternatives`: similar products, show
  separately. **Empty `matches` and `alternatives` = nothing stored yet, HTTP 200, not an error**: say "no prices found
  yet" and retry later (the existing `matchPolling.ts`: 3 retries at 3/6/12 s when empty).
- `stale:true` = some stored prices are older than the freshness threshold and a refresh may be running; show the
  prices with an "updating" note and ask again shortly (existing: 2 retries at 8/16 s). Do not poll faster: every
  re-ask can trigger a supermarket page refresh and the owner has a strict rule to minimise supermarket requests.
- `brand`, `productUrl` (always null today), `unitPrice` (null when pack size unknown) and `packSize` are nullable.
  `capturedAt` nullable ISO UTC. **`chainId` here is UPPER case** (`NEWWORLD`, `PAKNSAVE`, `WOOLWORTHS`).

### 4.5 Compare a list: `POST /api/pricing/compare`
Request (JSON, JWT):
```json
{ "region": "auckland",
  "items": [ { "itemId": "<shopping item uuid>", "quantity": 2 } ],
  "selectedChainId": "newworld",
  "allowedMethods": ["delivery", "click_and_collect"] }
```
- `region` required in effect (blank/unsupported -> 422). `items[].quantity` must be >= 1 (it is a primitive int; a
  missing value becomes 0). Duplicate item ids are summed.
- `selectedChainId` optional; **must be lower case** (`newworld|paknsave|woolworths`): the comparison matches it
  case-sensitively against the lower-case chain ids in the result. Omit it or send `null`/`""` to use the cheapest chain
  as the savings baseline (`selectedChainId` in the response then equals `cheapestChainId`).
- `allowedMethods` optional: values `"delivery"` and `"click_and_collect"` only (case-insensitive, trimmed). Omit or
  `[]` = all methods. `"in_store"` is invalid.

Response **200**:
```json
{ "cheapestChainId": "woolworths",
  "selectedChainId": "paknsave",
  "storeTotals": [
    { "chainId": "woolworths", "totalAmount": 17.7, "currency": "NZD", "itemsPriced": 3, "unavailableItemIds": [],
      "savingsAmount": 1.6, "totalWithFulfilment": 21.2, "bestMethod": "click_and_collect", "fulfilmentCost": 3.5,
      "fulfilmentOptions": [ { "method": "click_and_collect", "cost": 3.5, "totalAmount": 21.2 },
                             { "method": "delivery", "cost": 14.0, "totalAmount": 31.7 } ] },
    { "chainId": "paknsave", "totalAmount": 17.0, "currency": "NZD", "itemsPriced": 3, "unavailableItemIds": [],
      "savingsAmount": 0.0, "totalWithFulfilment": 22.0, "bestMethod": "click_and_collect", "fulfilmentCost": 5.0,
      "fulfilmentOptions": [ { "method": "click_and_collect", "cost": 5.0, "totalAmount": 22.0 } ] } ] }
```
(Numbers from the hand-calculated reference fixture in `docs/features/fulfilment-costs.md`.)
Semantics (verified in `ShoppingListComparisonService`, `SavingsCalculatorService`, gRPC controller):
- `totalAmount` = items only. `totalWithFulfilment` = items + the best allowed method's fee. `storeTotals` is sorted by
  `totalWithFulfilment`; `cheapestChainId` = first chain that has **every** requested item priced (full coverage), else
  the lowest total overall; `""` when `storeTotals` is empty.
- `savingsAmount = selected.totalWithFulfilment - chain.totalWithFulfilment + promo discount`. **It can be negative**
  (chain dearer than the selected one): show it as "costs $2.00 more", do not clamp. It is 0 for the baseline chain
  unless promos exist. If the selected chain is not in the results, the baseline is 0.
- `unavailableItemIds` = requested item ids this chain has no price for (map to names client-side).
  `itemsPriced < items.length` means partial coverage: never present such a chain as the "true cheapest" without a note.
- `bestMethod` is `null` (and `fulfilmentOptions` `[]`, `fulfilmentCost` 0, `totalWithFulfilment == totalAmount`) only
  when the chain has no fee data at all. A chain that offers methods but **none of the allowed ones is omitted** from
  `storeTotals` (e.g. PAK'nSAVE with `allowedMethods:["delivery"]`). If every chain is omitted: **200** with
  `"storeTotals": []` and `"cheapestChainId": ""` (not an error): show "No supermarket offers the chosen method(s)".
- Compare **reads stored data only** and the copies stored by `/match` are written in the background: a compare within
  ~1 s of a first `/match` may show an item as unavailable. Compare items that are `available` (or already matched).
- Errors, all **422** `{"error":"<message>"}` (show the message verbatim):
  - Unsupported/blank region **or an empty `items` list**:
    `You are outside Stocker's service area. Try again from a supported region.`
    (the empty-list case reuses this text on purpose; guard in the UI: disable Compare when the list is empty).
  - Unknown method: `Unknown fulfilment method 'in_store'. Valid methods: click_and_collect, delivery`.

### 4.6 Acceptance criteria
1. Add item -> immediate row "Looking for prices..." (pending), then prices appear without reload (SSE).
2. Reconnect/reload shows the same state (list refetch).
3. Match shows one row per chain (cheapest highlighted), alternatives separate, empty -> "no prices yet", stale ->
   "updating" note.
4. Compare shows per-supermarket total, items priced of N, unavailable item names, savings (positive, zero and negative
   rendered correctly), cheapest marked.
5. Unsupported region and empty list show the exact 422 message; no crash.
6. Savings baseline respects the user's pick (needs the casing fix, section 6).
**Definition of done:** the above verified; token refresh in place so a 20-minute session keeps working; lint + build.

## 4b. NEW: price search v2 (replaces how /match results are shown) - READ docs/features/price-search-v2.md

The owner found that /match "alternatives" were the same product at another chain, that "Milk 2L" and "Milk" gave the same
list, and that cancelling a search kept the server busy. The backend now offers (additive, /match still works):

- `GET /api/pricing/products?term=&itemId=[&category=][&limit=]` -> `{query, matchMethod, stale, products[], alternatives[]}`.
  One `Product` per distinct product with `prices` keyed by **lower-case chain id** (`newworld`, `paknsave`, `woolworths`),
  `unit`/`unitPrice` for comparing sizes, `cheapestChainId`, `productKey`. `alternatives` are genuinely different products
  (other brand, size or variant), never the same product at another chain. The exact JSON, nullability and rules are in
  `docs/features/price-search-v2.md` (copy its types into `src/pricing/types.ts`).
- Size and grade qualifiers are part of the TERM (`2L`, `500g`, `12 pack`, `dozen`, `size 7`); do not add parameters. With a qualifier
  only that size is a match; without one all sizes are listed, best unit price first.
- `PUT /api/pricing/items/{itemId}/product` with `{"productKey": "..."}` when the user picks a different product (from
  `products` or `alternatives`): that product's per-chain prices are stored for compare. Known limitation: compare can still show an
  earlier product's price for a chain whose earlier price is newer or that does not carry the chosen product (see the doc).
- UI: show one card per product with a price row per chain (cheapest highlighted, unit price shown), a separate "Alternatives" section,
  a "stale" hint when `stale` is true, and an empty state meaning "nothing stored yet" (not an error).
- **Cancellation:** when the user types a new search or leaves the page, ABORT the previous request (`AbortController`). The server then
  stops work for it (including queued supermarket page loads). Do this for search, compare and store-product requests.
- Data note: prices exist only for items already crawled. Carton eggs and Tim Tams will show nothing until the daily crawl has run.

## 5. Feature (d): Fulfilment costs UI

**User story:** compare shopping options including delivery / click-and-collect costs so I see the true cheapest.

### 5.1 UI (extend `ShoppingListCompare.tsx`, or a small `FulfilmentSelector` + table columns)
- Method checkboxes: "Delivery", "Click and collect" (both checked by default). Send `allowedMethods` only when not all
  are checked; if none checked, disable Compare (sending none means "all", which would surprise the user).
- Table columns per supermarket: Items total (`totalAmount`), Best option (`bestMethod` label: "Click and collect" /
  "Delivery" / "No fee data"), Fee (`fulfilmentCost`, "Free" when 0), **Total** (`totalWithFulfilment`, headline),
  Savings vs baseline. Expandable row or tooltip listing every `fulfilmentOptions` entry (method, fee, total).
- Recommendation line: "Cheapest including delivery/collection: <chain> via <best method> ($total)". Note when the
  shelf-cheapest chain differs from the true cheapest (e.g. PAK'nSAVE cheapest items, Woolworths cheapest basket).
- If a chain is missing from the results because of the method filter, say so in one line ("PAK'nSAVE only offers
  click and collect").
- States: loading, error (422 message), empty results message above.

### 5.2 Fee ladders shipped (services/pricing `application.yml`, `app.fulfilment`; source: supplied by the owner
2026-10-19, to be checked against the supermarkets' sites). A tier applies when items total >= `from` (inclusive).
Region -> island: auckland north, wellington north, christchurch south.

| Chain | Delivery | Click and collect |
|---|---|---|
| New World (north) | $14.00; $9.00 from $200 | $5.00; free from $50 |
| New World (south / Christchurch) | $15.00; $10.00 from $200 | flat $3.50 |
| PAK'nSAVE | not offered | $5.00 flat |
| Woolworths | $14.00; $9.00 from $200 | $3.50 flat |

UI copy may mention these thresholds ("free click and collect at New World from $50", "delivery drops to $9 from $200").
Do **not** hardcode fees in the frontend for calculation: always display what the API returns (config can change).

### 5.3 NOT modelled (say so in a small footnote)
Travel/driving cost (out of scope; there is no `in_store` method); New World Express Delivery ($9, max 30 items);
date-dependent surcharges (Woolworths Sunday/public-holiday +$2, 6-hour mid-week window, "Delivery Now" $19, $1.50
paper-bag fee); app promotions; store-specific fees (PAK'nSAVE same-day $9, "free over $150 at select stores");
Club+ requirements. Fees are estimates for planning.

### 5.4 Acceptance criteria (Trello)
Given known fees, the shown totals include them and the app names the true cheapest option: verify with a list where
the shelf-cheapest chain loses once fees are added; restricting to "Delivery" removes PAK'nSAVE; restricting to
"Click and collect" keeps all three; `bestMethod`/fee shown per chain; savings relative to the picked option can be
negative.
**Definition of done:** types updated (section 7), selector + columns done, 422 for `in_store` impossible from the UI.

## 6. Known casing trap: chain ids

| Source | Casing |
|---|---|
| `/api/pricing/match` `chainId` | `NEWWORLD`, `PAKNSAVE`, `WOOLWORTHS` |
| `/api/pricing/compare` `storeTotals[].chainId`, `cheapestChainId`, `selectedChainId` | lower case |
| shopping item `prices[].chainId` / `cheapest.chainId` | lower case |
| favorites `chainId` | lower case |
| `src/pricing/chains.ts` `CHAINS[].id` | UPPER case |

**Bug in the repo today:** `ShoppingListCompare` sends `selectedChainId: preferredChainId` with an UPPER-case id from
`CHAINS`; the backend compares it case-sensitively with lower-case ids, so the "Compare savings against" pick silently
gives a baseline of 0 and the "Your pick" tag never matches. Fix: send `preferredChainId.toLowerCase()`, and compare
with `.toLowerCase()` on both sides everywhere (add a `normChain(id)` helper in `chains.ts`; `chainLabel` already
upper-cases for lookup).

## 7. Types to add (copy-ready)

```ts
// src/pricing/types.ts additions / replacements
export type FulfilmentMethod = 'delivery' | 'click_and_collect'

export interface FulfilmentOption {
  method: FulfilmentMethod
  cost: number // delivery or click-and-collect fee
  totalAmount: number // items total + cost
}

export interface StoreTotal {
  chainId: string // lower case
  totalAmount: number // items only
  currency: string
  itemsPriced: number
  unavailableItemIds: string[]
  savingsAmount: number // vs the selected chain's totalWithFulfilment; may be negative
  totalWithFulfilment: number // equals totalAmount when no fee applies
  bestMethod: FulfilmentMethod | null // null = no fee data for the chain
  fulfilmentCost: number // cost of bestMethod (0 when null)
  fulfilmentOptions: FulfilmentOption[] // empty when bestMethod is null
}

export interface CompareShoppingListResult {
  storeTotals: StoreTotal[] // may be empty
  cheapestChainId: string // "" when storeTotals is empty
  selectedChainId: string
}

// src/lib/pantryApi.ts (new)
export interface PantryApiItem {
  pantry_item_id: string // snake_case
  user_id: string // snake_case
  product_id: string // snake_case
  quantity: number
  expirationDate: string | null // yyyy-mm-dd
  notifyDaysBefore: number | null // null = backend default of 3 days
}
export interface ProductApiItem {
  productId: string
  productName: string
  groceryType: string | null
  sellingWeightKg: number | null
  sellingVolumeL: number | null
}

// src/lib/notificationsApi.ts (already matches)
export interface ExpiryNotification {
  id: string
  pantryItemId: string
  productId: string
  expirationDate: string // yyyy-mm-dd, no time zone
  status: 'NEW' | 'ACKNOWLEDGED'
  createdAt: string
  acknowledgedAt: string | null
}
// FavoriteStore, StoreProduct, StoreProductsResponse, MatchView, MatchItemResponse,
// ServerShoppingItem, PriceView, ShoppingItemUpdate: already in the repo and match the gateway.
```

## 8. API client suggestion (matches the repo pattern)

Use the existing `authFetch(url, token, init)` (adds Bearer, `credentials:'include'`), `requestJson` /
`requestNoContent` in `src/pricing/api.ts`, and `readErrorMessage`. Changes:
- `readErrorMessage`: also accept `{ "message": string }` (inventory/notifications).
- New `src/lib/pantryApi.ts`: `listPantry(userId, token)`, `addPantryItem({userId, productId, quantity,
  expirationDate?, notifyDaysBefore?}, token)` (build `URLSearchParams`; skip empty values; only send
  `notifyDaysBefore` when a date is set), `updatePantryQuantity(id, quantity, token)`, `setPantryExpiry(id,
  expirationDate|null, notifyDaysBefore|null, token)`, `deletePantryItem(id, token)` (`requestNoContent`-style, the 200
  has no body), `searchProducts(query, token)`, `getProduct(id, token)`. Use `API_BASE`.
- `compareShoppingList` params gain `allowedMethods?: FulfilmentMethod[]`; body includes it only when non-empty;
  `selectedChainId` lower-cased.
- Token refresh helper used by `authFetch` (section 1.3). Keep a single in-flight refresh promise.

## 9. Ordered task list (small, testable steps; run lint + build after each)

1. Read the files in section 0; run `npm install`, `npm run lint`, `npm run build` to record the baseline.
2. `readErrorMessage` accepts `{message}`; add `src/lib/pantryApi.ts`; unit-check by reading the types against 2.2.
3. Move `Pantry.tsx` fully onto `/api/pantry-items` + `/api/products` via `authFetch`; show 400 messages. Grep
   `localhost:80` in `src/` must return nothing outside comments.
4. Move `Dashboard.tsx` pantry summary onto `GET /api/pantry-items?userId=`.
5. Silent token refresh + retry-once on 401 (section 1.3); verify with `STOCKER_JWT_ACCESS_TTL=60` on the gateway/identity
   (backend env) that the list, polling and SSE survive several expiries.
6. Fix the chain casing bug (section 6) and add `normChain`.
7. Update `types.ts` for fulfilment; extend `compareShoppingList`; build the method selector + new columns +
   recommendation line + footnote (section 5).
8. Compare UX guards: disable on empty list / no methods ticked; map `unavailableItemIds` to names; show negative savings.
9. Verify expiry feature against 2.5 (add/edit/clear date, threshold validation messages, badge, acknowledge).
10. Verify favorites against 3.3; verify shopping/SSE against 4.6, including a 15+ minute session.
11. Update `CLAUDE.md`, `AGENTS.md`, `src/pricing/README.md` ("Things that bypass the gateway" no longer true; fulfilment
    documented).
12. Final: `npm run lint && npm run build`; report what was verified manually vs not.

## 10. Tests

The repo has **no test runner** (confirm in `package.json`). Do not claim tests ran that do not exist. Options, in order:
(a) add `vitest` as a devDependency only if the owner agrees (free, small) and cover the pure logic: SSE block parser
(`sseStream.ts` `parseBlock`), `parseShoppingItemUpdate`, `normChain`, a fulfilment display helper (best-method label,
fee text, negative savings), `pantryApi` query building (threshold only with a date); (b) otherwise a written manual
checklist from the acceptance criteria above, with results. Mandatory either way: `npm run lint` clean, `npm run build`
(= `tsc -b && vite build`) passes with zero errors, and a manual run against the live stack.

## 11. Run it

Start the whole backend stack as described in **`docs/MANUAL_TESTING.md`** in the backend repo (scripts live in
`scripts/manual-test/`; another agent maintains them: follow them, do not edit them or invent steps). Expect the
gateway at `http://localhost:8080` and run the frontend with `npm run dev` at **`http://localhost:5173`** (the only
dev origin CORS allows by default). Use a fresh registered user. Inventory's catalog URL is now configurable and the
compose file passes it, so pantry add works in docker. Price data depends on the crawl: with an empty database, match
returns empty lists and compare shows items unavailable; that is expected. Do not trigger live supermarket requests
yourself; the owner must approve those first.

## 12. Known backend gaps / risks (tell the owner; do not work around silently)

1. Inventory ignores `X-User-Id` and trusts the `userId` request param; item PUT/DELETE are not ownership-checked.
2. Pantry JSON mixes snake_case (`pantry_item_id`, `user_id`, `product_id`) and camelCase fields.
3. Inventory/notifications errors are `{"message"}`, everything else `{"error"}`; unknown pantry item is a 400, an
   unknown notification a 404.
4. No push for notifications (poll only); the scan runs every 15 min so "on time" means within that interval.
5. Notifications carry only `productId`; names need a catalog lookup per product.
6. `/match` returns UPPER-case chain ids, compare/shopping/favorites lower-case.
7. Empty compare list returns the "outside service area" message (misleading text, same acceptance wording).
8. Shopping items cannot be deleted or ticked server-side; push delivery is best effort (single gateway instance, no replay).
9. A shopping item can stay `pending` for good (one 8 s attempt, then only Kafka backfill); price data depends on the
   daily crawl (03:00 NZ, 80 page/chain/day cap), so some items have no prices.
10. `stale` threshold: gateway docs say 12 h, the reliability doc says `stale-after` 26 h; trust the flag, not a number.
11. Access token lifetime is 15 min and there is no server-side session refresh besides the cookie endpoint.
12. Refresh cookie is `SameSite=Lax`, not `Secure`, scoped to the gateway host: it works for `localhost:5173` ->
    `localhost:8080`, not across different sites (e.g. a LAN IP page calling a different host).
13. Two CORS layers exist in the gateway (`SecurityConfig` allows credentials; `CorsConfig` does not set it): if a
    browser CORS error appears on credentialed calls, report it to the backend owner.
14. Fee tables are owner-supplied, estimates only; many real-world fees are not modelled (section 5.3).
15. Non-identity validation failures from the identity service (e.g. short password) have not been inspected for their
    exact body shape: show `error` or `message` if present, else the generic status text.
