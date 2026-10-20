# Inventory: free-text (custom) pantry items

`POST /pantry-items` (gateway: `/api/pantry-items`) no longer needs a catalog product. Either `productId` or `name` is required.
Accepts the original query params and/or a JSON body (body wins). The old query form `?productId=&quantity=[&expirationDate=&notifyDaysBefore=]` still works. The user is always the gateway-verified `X-User-Id`; a `userId` parameter or body field is ignored.

```json
{ "productId": null, "name": "Nana's jam", "category": "preserves", "unit": "jar",
  "quantity": 2, "expirationDate": "2026-11-01", "notifyDaysBefore": 5 }
```
`quantity` >= 1 (required); `notifyDaysBefore` >= 0 and needs an `expirationDate`. Missing name and productId, or bad numbers -> 400 `{"message": "..."}`.
With a `productId` the catalog is a best-effort name source only (name given by the caller wins); an outage or 404 never blocks the add. The same productId merges into one row; free-text items are always new rows.

Response (domain JSON; ids keep their legacy snake_case, the rest is camelCase; unset values are null):
```json
{ "pantry_item_id": "uuid", "user_id": "uuid", "product_id": null, "name": "Nana's jam", "category": "preserves",
  "unit": "jar", "quantity": 2, "expirationDate": "2026-11-01", "notifyDaysBefore": 5 }
```

`GET /internal/pantry-items/expiring?asOf=YYYY-MM-DD` (unchanged, internal) now also returns `name`, `category`, `unit`:
```json
[ { "pantryItemId": "uuid", "userId": "uuid", "productId": null, "quantity": 1, "expirationDate": "2026-10-21",
    "notifyDaysBefore": 3, "name": "Nana's jam", "category": null, "unit": "jar" } ]
```

Migration `V3__custom_pantry_items.sql` (history table `flyway_schema_history_inventory`): `product_id` DROP NOT NULL, new nullable `name`, `category`, `unit`.
