# Click-and-collect, delivery and travel costs (contract)

Story: "As a user, I want Stocker to include travel/delivery/click and collect costs when comparing shopping options so
that I know the true cheapest option."

Acceptance tests (Trello): given data with known **travel** / **delivery** / **click-and-collect** costs, when the system
calculates the total cost of each option, Stocker correctly identifies the true cheapest option, including those costs.

## Model

Each chain can be reached three ways: `in_store` (cost = travel), `delivery` (tiered delivery fee), `click_and_collect` (tiered fee). Per chain: **option total = items total + method cost**. The chain's
**best option** is its cheapest allowed method; `totalWithFulfilment` is that total. The recommendation ("true cheapest")
and the savings use `totalWithFulfilment`. With no fees configured every chain has `in_store` at cost 0, so results are
identical to today (all existing tests must keep passing unchanged).

## Config (pricing, `application.yml`) - values are data, not code

Fees are TIERED and can differ by island. Real values shipped (source: supplied by the owner 2026-10-19; check against
the supermarkets' sites):

```yaml
app:
  fulfilment:
    region-islands: { auckland: north, wellington: north, christchurch: south }   # unknown region = chain defaults
    chains:
      <chain>:
        travel-cost: 0.00
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
default applies. A tier boundary is inclusive (items total exactly equal to `from` gets that tier). `travel-cost`
defaults to 0 (no per-trip figure yet; needs one from the owner). Shipped: New World (north: delivery 14/9 at 200,
click-and-collect 5/0 at 50; south: delivery 15/10, click-and-collect flat 3.50), PAK'nSAVE (click-and-collect 5.00,
no delivery), Woolworths (delivery 14/9 at 200, click-and-collect 3.50).

### Not modelled yet

New World Express Delivery ($9, max 30 items, 60 min); date-dependent surcharges (Woolworths Sunday/public-holiday +$2,
mid-week 6-hour window, "Delivery Now" $19, $1.50 paper-bag fee); app promotions; store-specific fees (PAK'nSAVE
same-day collection $9, "free over $150 at select stores"); Club+ requirement.

## Java (pricing)

- `FulfilmentMethod` enum: `IN_STORE("in_store")`, `DELIVERY("delivery")`, `CLICK_AND_COLLECT("click_and_collect")`, with `wireName()`.
- `FulfilmentProperties` (`@ConfigurationProperties("app.fulfilment")`): `Map<String, ChainFees> chains` (lower-case chain id);
  `Map<String,String> regionIslands`; `ChainFees { BigDecimal travelCost = 0; List<Tier> delivery; List<Tier> clickAndCollect; Map<String,IslandFees> islands; }`,
  `Tier { BigDecimal from; BigDecimal fee; }`, `IslandFees { List<Tier> delivery; List<Tier> clickAndCollect; }`.
- `ShoppingListComparisonService.FulfilmentOption(FulfilmentMethod method, BigDecimal cost, BigDecimal totalAmount)`.
- `ChainTotal` gains `List<FulfilmentOption> fulfilmentOptions`, `FulfilmentOption bestOption`, `BigDecimal totalWithFulfilment`.
  Keep the existing 7-argument constructor (zero-fee defaults: options = [in_store, 0], best = that, totalWithFulfilment = totalAmount)
  so existing tests compile unchanged.
- `compare(items, region)` keeps working; add `compare(items, region, Set<FulfilmentMethod> allowedMethods)` (empty/null = all).
  `ShoppingListComparisonService` keeps its 2-argument constructor (empty fees) and gains one taking `FulfilmentProperties`.
- Sorting and `cheapestChainId`: full-coverage chains first by `totalWithFulfilment` (ties: `totalAmount`, then chain id);
  if no chain has full coverage, the lowest `totalWithFulfilment` overall. A chain with **no allowed method** is excluded from
  the recommendation and is returned with `bestOption = null`, `totalWithFulfilment = totalAmount`.
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

Gateway `POST /api/pricing/compare`: request body adds optional `allowedMethods: ["in_store","delivery","click_and_collect"]`;
each `storeTotals[]` adds `totalWithFulfilment`, `bestMethod` (null when none), `fulfilmentCost`,
`fulfilmentOptions: [{method, cost, totalAmount}]`. Unknown method names in `allowedMethods` are a 422 `{"error": ...}`.

## Reference fixture (hand-calculated; use it in tests)

Flat example fees below are test data (`FulfilmentFixtures.fees`: a flat fee is the single tier `[0 -> fee]`, a free-over
threshold adds the tier `[threshold -> 0]`); they are not the shipped real values.

Items total: paknsave 17.00, woolworths 17.70, newworld 19.00 (see `PriceFixtures` in the acceptance tests).
- Travel: paknsave 4.00, woolworths 0.50, newworld 0.00 -> in_store totals 21.00 / 18.20 / 19.00 -> cheapest **woolworths**.
- Delivery: paknsave 9.00, woolworths 6.00 (free over 20), newworld 5.00, `allowedMethods=[delivery]` -> 26.00 / 23.70 / 24.00 -> **woolworths**.
- Click and collect: paknsave 3.00, woolworths 4.50, newworld 0.00, `allowedMethods=[click_and_collect]` -> 20.00 / 22.20 / 19.00 -> **newworld**.
- Mixed (all methods allowed): paknsave {travel 4, C&C 3, delivery 9}, woolworths {travel 0.5, C&C 4.5, delivery 6}, newworld {travel 0, C&C 0, delivery 5}:
  best per chain = paknsave C&C 20.00, woolworths in_store 18.20, newworld 19.00 -> cheapest **woolworths**, `bestMethod` in_store.
  newworld ties at 19.00 between in_store and click_and_collect: **ties between methods go in the order in_store, click_and_collect, delivery**, so its best is in_store.

## Frontend types

Hand-mirrored from the gateway JSON in `../Stocker-Frontend/src/pricing/types.ts`. Add (existing fields stay as they are):

```ts
export type FulfilmentMethod = 'in_store' | 'delivery' | 'click_and_collect';

export interface FulfilmentOption {
  method: FulfilmentMethod;
  cost: number;        // travel cost, delivery fee or click-and-collect fee
  totalAmount: number; // items total + cost
}

// Added to the existing StoreTotal (storeTotals[] entry of POST /api/pricing/compare):
export interface StoreTotal {
  // ...existing: chainId, totalAmount, currency, itemsPriced, unavailableItemIds, savingsAmount
  totalWithFulfilment: number;           // equals totalAmount when no fees apply
  bestMethod: FulfilmentMethod | null;   // null = no allowed method available for this chain
  fulfilmentCost: number;                // cost of bestMethod
  fulfilmentOptions: FulfilmentOption[]; // every allowed option; empty when bestMethod is null
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
- A chain with `bestMethod === null` cannot be used with the chosen methods: show it greyed out, never as cheapest.
- The recommendation (`cheapestChainId`) already accounts for fulfilment costs: label it "cheapest including
  travel/delivery/collection" and show its best method. `savingsAmount` is relative to the selected chain's
  `totalWithFulfilment`.
- Let the user pick the methods they would use (checkboxes -> `allowedMethods`); with none ticked send nothing.
