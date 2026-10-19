# Click-and-collect, delivery and travel costs (contract)

Story: "As a user, I want Stocker to include travel/delivery/click and collect costs when comparing shopping options so
that I know the true cheapest option."

Acceptance tests (Trello): given data with known **travel** / **delivery** / **click-and-collect** costs, when the system
calculates the total cost of each option, Stocker correctly identifies the true cheapest option, including those costs.

## Model

Each chain can be reached three ways: `in_store` (cost = travel), `delivery` (delivery fee, optionally free over a
basket value), `click_and_collect` (fee). Per chain: **option total = items total + method cost**. The chain's
**best option** is its cheapest allowed method; `totalWithFulfilment` is that total. The recommendation ("true cheapest")
and the savings use `totalWithFulfilment`. With no fees configured every chain has `in_store` at cost 0, so results are
identical to today (all existing tests must keep passing unchanged).

## Config (pricing, `application.yml`) - values are data, not code

```yaml
app:
  fulfilment:
    chains:
      newworld:     { travel-cost: 0.00, delivery-fee: ,     click-and-collect-fee: ,     delivery-free-over: }
      paknsave:     { travel-cost: 0.00, delivery-fee: ,     click-and-collect-fee: ,     delivery-free-over: }
      woolworths:   { travel-cost: 0.00, delivery-fee: ,     click-and-collect-fee: ,     delivery-free-over: }
```
`travel-cost` defaults to 0. An empty `delivery-fee` / `click-and-collect-fee` = method not offered. `delivery-free-over`
(optional): delivery costs 0 when the items total is at least this. **Do not invent real fee values**: ship the keys with
empty values and example values only in tests and comments; the team fills in real fees.

## Java (pricing)

- `FulfilmentMethod` enum: `IN_STORE("in_store")`, `DELIVERY("delivery")`, `CLICK_AND_COLLECT("click_and_collect")`, with `wireName()`.
- `FulfilmentProperties` (`@ConfigurationProperties("app.fulfilment")`): `Map<String, ChainFees> chains` (lower-case chain id);
  `ChainFees { BigDecimal travelCost = 0; BigDecimal deliveryFee; BigDecimal clickAndCollectFee; BigDecimal deliveryFreeOver; }`.
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

Items total: paknsave 17.00, woolworths 17.70, newworld 19.00 (see `PriceFixtures` in the acceptance tests).
- Travel: paknsave 4.00, woolworths 0.50, newworld 0.00 -> in_store totals 21.00 / 18.20 / 19.00 -> cheapest **woolworths**.
- Delivery: paknsave 9.00, woolworths 6.00 (free over 20), newworld 5.00, `allowedMethods=[delivery]` -> 26.00 / 23.70 / 24.00 -> **woolworths**.
- Click and collect: paknsave 3.00, woolworths 4.50, newworld 0.00, `allowedMethods=[click_and_collect]` -> 20.00 / 22.20 / 19.00 -> **newworld**.
- Mixed (all methods allowed): paknsave {travel 4, C&C 3, delivery 9}, woolworths {travel 0.5, C&C 4.5, delivery 6}, newworld {travel 0, C&C 0, delivery 5}:
  best per chain = paknsave C&C 20.00, woolworths in_store 18.20, newworld 19.00 -> cheapest **woolworths**, `bestMethod` in_store.
  newworld ties at 19.00 between in_store and click_and_collect: **ties between methods go in the order in_store, click_and_collect, delivery**, so its best is in_store.
