# Click-and-collect and delivery costs (contract)

Story: "As a user, I want Stocker to include delivery/click and collect costs when comparing shopping options so
that I know the true cheapest option."

Acceptance tests (Trello): given data with known **delivery** / **click-and-collect** costs, when the system
calculates the total cost of each option, Stocker correctly identifies the true cheapest option, including those costs.
(The original third criterion, known **travel** costs, no longer applies: see "Out of scope: travel cost".)

## Out of scope: travel cost

Travel cost (the cost of driving to a store) is deliberately not modelled, and there is no `in_store` method. Only
`delivery` and `click_and_collect` exist; `in_store` in `allowed_methods` is an unknown method name (INVALID_ARGUMENT).

## Model

Each chain can be reached two ways: `delivery` (tiered delivery fee) and `click_and_collect` (tiered fee). Per chain,
the **options** are the methods the chain offers (a non-empty fee ladder for the region's island) intersected with the
allowed methods (empty/absent = all). **Option total = items total + method fee.** The chain's **best option** is its
cheapest option (ties: `click_and_collect`, then `delivery`); `totalWithFulfilment` = items total + best option's fee.
The recommendation ("true cheapest") and the savings use `totalWithFulfilment`.

Two special cases:

- **Chain with no fee configuration at all** (offers no method: unknown chain or empty config): no fee applies. It stays
  eligible with `bestOption = null`, `fulfilmentOptions = []`, `totalWithFulfilment = totalAmount`.
- **Chain that offers methods but none is allowed** (e.g. PAK'nSAVE offers only click-and-collect and the user allowed
  only delivery): **left out of the results entirely** - not in `chainTotals` / `store_totals`, not in the savings
  calculation, never `cheapestChainId`. If every chain is left out, `store_totals` is empty and `cheapestChainId` is `""`
  (the gateway returns an empty `storeTotals` array, not an error). If the user's `selected_chain_id` was left out, it is
  still echoed as `effective_selected_chain_id`, but it is not in the results, so the savings baseline is 0 (the existing
  behaviour for any selected chain that is not in the results): each remaining chain's savings = discount - its total.

## Config (pricing, `application.yml`) - values are data, not code

Fees are TIERED and can differ by island. Real values shipped (source: supplied by the owner 2026-10-19; check against
the supermarkets' sites):

```yaml
app:
  fulfilment:
    region-islands: { auckland: north, wellington: north, christchurch: south }   # unknown region = chain defaults
    chains:
      <chain>:
        delivery:           # tier with the greatest `from` <= items total applies (inclusive); omitted/empty = not offered
          - { from: 0, fee: 14.00 }
          - { from: 200, fee: 9.00 }
        click-and-collect:
          - { from: 0, fee: 5.00 }
          - { from: 50, fee: 0.00 }
        islands:            # optional per-island override of delivery and/or click-and-collect
          south:
            delivery: [ { from: 0, fee: 15.00 }, { from: 200, fee: 10.00 } ]
            click-and-collect: [ { from: 0, fee: 3.50 } ]
```
Island = `region-islands[region lower-cased, trimmed]`; a chain's island override of a method wins, otherwise the chain
default applies. A tier boundary is inclusive (items total exactly equal to `from` gets that tier). Shipped: New World (north: delivery 14/9 at 200,
click-and-collect 5/0 at 50; south: delivery 15/10, click-and-collect flat 3.50), PAK'nSAVE (click-and-collect 5.00,
no delivery), Woolworths (delivery 14/9 at 200, click-and-collect 3.50).

### Not modelled yet

New World Express Delivery ($9, max 30 items, 60 min); date-dependent surcharges (Woolworths Sunday/public-holiday +$2,
mid-week 6-hour window, "Delivery Now" $19, $1.50 paper-bag fee); app promotions; store-specific fees (PAK'nSAVE
same-day collection $9, "free over $150 at select stores"); Club+ requirement.

## Java (pricing)

- `FulfilmentMethod` enum (declaration order = tie-break order): `CLICK_AND_COLLECT("click_and_collect")`, `DELIVERY("delivery")`, with `wireName()`; an unknown name throws `IllegalArgumentException` listing "click_and_collect, delivery".
- `FulfilmentProperties` (`@ConfigurationProperties("app.fulfilment")`): `Map<String, ChainFees> chains` (lower-case chain id);
  `Map<String,String> regionIslands`; `ChainFees { List<Tier> delivery; List<Tier> clickAndCollect; Map<String,IslandFees> islands; }`,
  `Tier { BigDecimal from; BigDecimal fee; }`, `IslandFees { List<Tier> delivery; List<Tier> clickAndCollect; }`.
- `ShoppingListComparisonService.FulfilmentOption(FulfilmentMethod method, BigDecimal cost, BigDecimal totalAmount)`.
- `ChainTotal` gains `List<FulfilmentOption> fulfilmentOptions`, `FulfilmentOption bestOption`, `BigDecimal totalWithFulfilment`.
  Keep the existing 7-argument constructor (no-fee defaults: options = [], best = null, totalWithFulfilment = totalAmount)
  so existing tests compile unchanged.
- `compare(items, region)` keeps working; add `compare(items, region, Set<FulfilmentMethod> allowedMethods)` (empty/null = all).
  `ShoppingListComparisonService` keeps its 2-argument constructor (empty fees) and gains one taking `FulfilmentProperties`.
- Sorting and `cheapestChainId`: full-coverage chains first by `totalWithFulfilment` (ties: `totalAmount`, then chain id);
  if no chain has full coverage, the lowest `totalWithFulfilment` overall. A chain that offers methods but has **no allowed method** is removed from
  the results altogether; a chain that offers no method at all stays eligible (see Model).
- `SavingsCalculatorService`: savings = `(selected.totalWithFulfilment - chain.totalWithFulfilment) + discountAmount`.

## Wire contract

Proto (`price_record.proto`; identical field numbers in all three copies: pricing, gateway, shopping), append only:

```proto
message FulfilmentOption { string method = 1; double cost = 2; double total_amount = 3; }
// CompareShoppingListRequest: repeated string allowed_methods = 4;   // empty = all
// StoreTotal:
//   double total_with_fulfilment = 7;  string best_method = 8;  double fulfilment_cost = 9;
//   repeated FulfilmentOption fulfilment_options = 10;
```
`total_amount` stays the items total; `savings_amount` is relative to the selected chain's `total_with_fulfilment`.

Gateway `POST /api/pricing/compare`: request body adds optional `allowedMethods: ["delivery","click_and_collect"]`;
each `storeTotals[]` adds `totalWithFulfilment`, `bestMethod` (null when none), `fulfilmentCost`,
`fulfilmentOptions: [{method, cost, totalAmount}]`. Unknown method names in `allowedMethods` are a 422 `{"error": ...}`.

## Reference fixture (hand-calculated; use it in tests)

Flat example fees below are test data (`FulfilmentFixtures.fees`: a flat fee is the single tier `[0 -> fee]`, a free-over
threshold adds the tier `[threshold -> 0]`); they are not the shipped real values.

Items total: paknsave 17.00, woolworths 17.70, newworld 19.00 (see `PriceFixtures` in the acceptance tests).

Shipped north-island fees (`FulfilmentFixtures.mixedReference()`, region `auckland`): at these basket sizes (under 50 and under 200)
New World delivery 14.00 / click-and-collect 5.00; PAK'nSAVE click-and-collect 5.00 (no delivery); Woolworths delivery 14.00 /
click-and-collect 3.50.

- All methods allowed: paknsave C&C 17.00+5.00 = 22.00; woolworths C&C 17.70+3.50 = 21.20 (delivery 31.70); newworld C&C 19.00+5.00 = 24.00
  (delivery 33.00). Order: woolworths 21.20, paknsave 22.00, newworld 24.00 -> cheapest **woolworths**, every `bestMethod` `click_and_collect`.
  PAK'nSAVE has the cheapest shelf but not the cheapest basket.
- `allowedMethods=[delivery]`: woolworths 31.70, newworld 33.00 -> **woolworths**. PAK'nSAVE offers methods but not delivery, so it is
  left out of the results (and the savings) entirely.
- `allowedMethods=[click_and_collect]`: woolworths 21.20, paknsave 22.00, newworld 24.00 -> **woolworths**.
- Savings relative to a selected paknsave (22.00): woolworths (22.00 - 21.20) + 0.80 discount = 1.60; newworld 22.00 - 24.00 = -2.00.
- `allowedMethods=["in_store"]` -> INVALID_ARGUMENT "... Valid methods: click_and_collect, delivery".
- Ties: with equal totals `click_and_collect` beats `delivery`.
- Chain with no fee configuration (e.g. unknown chain): no fee, eligible, `totalWithFulfilment` = items total.

Flat example fees used in `ShoppingListFulfilmentTest` / `FulfilmentScenarioTest` (`FulfilmentFixtures.fees`: flat fee = single tier
`[0 -> fee]`, free-over threshold adds `[threshold -> 0]`) are test data, not the shipped real values.

## Frontend types

Hand-mirrored from the gateway JSON in `../Stocker-Frontend/src/pricing/types.ts`. Add (existing fields stay as they are):

```ts
export type FulfilmentMethod = 'delivery' | 'click_and_collect';

export interface FulfilmentOption {
  method: FulfilmentMethod;
  cost: number;        // delivery fee or click-and-collect fee
  totalAmount: number; // items total + cost
}

// Added to the existing StoreTotal (storeTotals[] entry of POST /api/pricing/compare):
export interface StoreTotal {
  // ...existing: chainId, totalAmount, currency, itemsPriced, unavailableItemIds, savingsAmount
  totalWithFulfilment: number;           // equals totalAmount when no fee applies
  bestMethod: FulfilmentMethod | null;   // null = no fee applies (no fee data for the chain, or no allowed method)
  fulfilmentCost: number;                // cost of bestMethod (0 when null)
  fulfilmentOptions: FulfilmentOption[]; // every allowed option the chain offers; empty when bestMethod is null
}

// Added to the compare request body:
export interface CompareRequest {
  // ...existing: items, region, selectedChainId?
  allowedMethods?: FulfilmentMethod[];   // omit / empty = all methods
}
```

Errors: an unknown method name or an out-of-area region is HTTP 422 `{ "error": string }` (show the message).

### What the UI should show

- Per supermarket: the best method (`bestMethod`, e.g. "Click and collect") and its cost (`fulfilmentCost`), with
  `totalWithFulfilment` as the headline total and `totalAmount` as the "items" line. Optionally list `fulfilmentOptions`
  so the user sees the alternatives.
- A chain with `bestMethod === null` has no fee data (no fee applied). A chain that offers methods but none of the
  chosen ones is not returned at all, so the UI may show fewer supermarkets than before (possibly none: an empty
  `storeTotals` and `cheapestChainId` `""`; explain that no supermarket offers the chosen methods).
- The recommendation (`cheapestChainId`) already accounts for fulfilment costs: label it "cheapest including
  delivery/collection" and show its best method. `savingsAmount` is relative to the selected chain's
  `totalWithFulfilment`.
- Let the user pick the methods they would use (checkboxes -> `allowedMethods`); with none ticked send nothing.
