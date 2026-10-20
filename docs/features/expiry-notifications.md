# Expiry notifications (notifications service, :8085)

The notifications service polls inventory every `STOCKER_NOTIFICATIONS_SCAN_INTERVAL` (default PT15M) and creates one
notification per (pantry item, expiration date). Users read and acknowledge them through the gateway.

## Inventory contract (service-to-service, not via the gateway)

`GET /internal/pantry-items/expiring?asOf=YYYY-MM-DD` on inventory (`:8087`) returns every user's items whose window has opened:

```json
[ { "pantryItemId": "uuid", "userId": "uuid", "productId": null, "quantity": 1, "expirationDate": "2026-10-21",
    "notifyDaysBefore": 3, "name": "Nana's jam", "category": null, "unit": "jar" } ]
```

`productId` is null for free-text pantry items. This endpoint takes no user header and is not routed by the gateway;
the user-facing endpoints are: the gateway verifies the JWT and forwards `X-User-Id`, and notifications trusts only that header
(never expose it directly).

## Notification API (via gateway, `X-User-Id` set from the JWT)

`GET /api/notifications` (service path `/notifications`) -> list, newest first. Each element:

```json
{ "id": "uuid", "pantryItemId": "uuid", "productId": null, "itemName": "Nana's jam", "category": null, "unit": "jar",
  "expirationDate": "2026-10-21", "status": "NEW", "createdAt": "...", "acknowledgedAt": null }
```

New fields `itemName`, `category`, `unit` (nullable). `productId` is now nullable: show `itemName` for the label, do not
look the product up when `productId` is null. `POST /api/notifications/{id}/acknowledge` is unchanged.

## Behaviour

- One failing item (for example a database error on save) is logged and skipped; the rest of the scan still runs.
- Items that expired more than `app.notifications.skip-expired-older-than-days` days ago (env
  `STOCKER_NOTIFICATIONS_SKIP_EXPIRED_OLDER_THAN_DAYS`, default 7) are not notified, so a first scan on an old pantry
  does not flood the user.
- Schema: Flyway `V2__product_id_nullable_and_item_name.sql` (product_id nullable; adds item_name, category, unit).
  Existing rows keep null in the new columns.
