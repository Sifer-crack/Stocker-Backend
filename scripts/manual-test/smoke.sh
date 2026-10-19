#!/usr/bin/env bash
# End-to-end smoke test through the GATEWAY ONLY (default http://localhost:8080). Needs curl and python3.
# Registers a throwaway user, exercises expiry notifications, favorite stores, store products, shopping list,
# match and compare (fulfilment costs). Prints PASS/FAIL/SKIP per step; never prints the JWT.
# It never calls /api/pricing/search (that uses the live price provider) and sends no supermarket request.
set -euo pipefail

BASE="${GATEWAY_URL:-http://localhost:8080}"
NOTIFY_WAIT="${NOTIFY_WAIT_SECONDS:-90}"
STAMP="$(date +%s)"
EMAIL="manual-test-${STAMP}@example.test"
# Throwaway password generated per run; only ever sent to the local gateway.
PASSWORD="Mt-${STAMP}-$RANDOM$RANDOM"

TMP="$(mktemp -d)"
chmod 700 "$TMP"
AUTH="$TMP/auth.hdr"
BODY="$TMP/body.json"
STATUS=""
PASSES=0; FAILS=0; SKIPS=0
TOKEN_OK=0

# Cleanup lists (id strings), filled as things are created.
PANTRY_IDS=(); PRODUCT_IDS=(); FAV_IDS=()

cleanup_tmp() { rm -rf "$TMP"; }
trap cleanup_tmp EXIT

pass() { PASSES=$((PASSES + 1)); printf 'PASS  %s\n' "$1"; }
fail() { FAILS=$((FAILS + 1)); printf 'FAIL  %s%s\n' "$1" "${2:+  ($2)}"; }
skip() { SKIPS=$((SKIPS + 1)); printf 'SKIP  %s%s\n' "$1" "${2:+  ($2)}"; }

# call METHOD PATH [JSON_BODY]: sets STATUS and writes the response to $BODY. Auth header read from a file.
call() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-sS -m 30 -o "$BODY" -w '%{http_code}' -X "$method" "$BASE$path")
  if [ "$TOKEN_OK" -eq 1 ]; then args+=(-H "@$AUTH"); fi
  if [ -n "$data" ]; then args+=(-H 'Content-Type: application/json' --data "$data"); fi
  STATUS="$(curl "${args[@]}" 2>/dev/null)" || STATUS="000"
}

# jget PYEXPR: evaluate a python expression with `d` = parsed response JSON; prints result ("" on error).
jget() {
  python3 -I -c '
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    r=eval(sys.argv[2])
    print("" if r is None else r)
except Exception:
    print("")
' "$BODY" "$1"
}

expect_status() { # name, wanted-regex
  if [[ "$STATUS" =~ ^($2)$ ]]; then pass "$1"; return 0; fi
  fail "$1" "HTTP $STATUS, expected $2"; return 1
}

echo "Stocker smoke test against $BASE  (user: $EMAIL)"
echo "------------------------------------------------------------"

# --- 0. gateway reachable ---------------------------------------------------------------
call GET /healthz
if ! expect_status "gateway /healthz" 200; then
  echo "Gateway not reachable: is the stack up? (scripts/manual-test/stack-up.sh)"; exit 1
fi

# --- 1. identity ------------------------------------------------------------------------
call POST /api/identity/register "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"firstName\":\"Manual\",\"lastName\":\"Test\"}"
expect_status "register throwaway user" 201 || { echo "Cannot continue without a user."; exit 1; }

call POST /api/identity/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}"
if expect_status "login" 200; then
  jget 'd["accessToken"]' | { read -r t; [ -n "$t" ] && { umask 077; printf 'Authorization: Bearer %s\n' "$t" > "$AUTH"; }; } || true
  if [ -s "$AUTH" ]; then TOKEN_OK=1; pass "JWT received (not printed)"; else fail "JWT received" "no accessToken in login response"; fi
fi
[ "$TOKEN_OK" -eq 1 ] || { echo "Cannot continue without a JWT."; exit 1; }

call GET /api/identity/me
expect_status "GET /api/identity/me" 200 || true
USER_ID="$(jget 'd["id"]')"
if [ -n "$USER_ID" ]; then pass "user id resolved"; else fail "user id resolved"; echo "Cannot continue without a user id."; exit 1; fi

call GET /api/pantry-items
if [ "$STATUS" = "401" ] || [ "$STATUS" = "403" ]; then fail "authenticated route without userId param" "unexpected $STATUS"; else pass "JWT accepted by gateway (pantry route reachable)"; fi

# --- 2. expiry notifications --------------------------------------------------------------
TODAY_AKL="$(python3 -I -c 'import datetime,zoneinfo;print(datetime.datetime.now(zoneinfo.ZoneInfo("Pacific/Auckland")).date())')"
plus() { python3 -I -c 'import datetime,sys;print(datetime.date.fromisoformat(sys.argv[1])+datetime.timedelta(days=int(sys.argv[2])))' "$TODAY_AKL" "$1"; }

LAST_ID=""
make_product() { # name -> sets LAST_ID (empty on failure); records for cleanup
  LAST_ID=""
  call POST "/api/products?productName=ManualTest-$1-$STAMP"
  if [[ "$STATUS" =~ ^(200|201)$ ]]; then LAST_ID="$(jget 'd["productId"]')"; fi
  if [ -n "$LAST_ID" ]; then PRODUCT_IDS+=("$LAST_ID"); fi
}

make_product due; P_DUE="$LAST_ID"
make_product far; P_FAR="$LAST_ID"
make_product nodate; P_NONE="$LAST_ID"
if [ -n "$P_DUE" ] && [ -n "$P_FAR" ] && [ -n "$P_NONE" ]; then pass "catalog: 3 throwaway products created"; else fail "catalog: 3 throwaway products created" "POST /api/products failed"; fi

add_pantry() { # product, query-suffix -> sets LAST_ID
  LAST_ID=""
  call POST "/api/pantry-items?userId=$USER_ID&productId=$1&quantity=1$2"
  if [[ "$STATUS" =~ ^(200|201)$ ]]; then LAST_ID="$(jget 'd["pantry_item_id"]')"; fi
  if [ -n "$LAST_ID" ]; then PANTRY_IDS+=("$LAST_ID"); fi
}

DUE_DATE="$(plus 2)"; FAR_DATE="$(plus 30)"
I_DUE=""; I_FAR=""; I_NONE=""
if [ -n "$P_DUE" ]; then
  add_pantry "$P_DUE" "&expirationDate=$DUE_DATE&notifyDaysBefore=3"; I_DUE="$LAST_ID"
  if [ -n "$I_DUE" ]; then
    saved_d="$(jget 'd["expirationDate"]')"; saved_n="$(jget 'd["notifyDaysBefore"]')"
    if [ "$saved_d" = "$DUE_DATE" ]; then pass "expiry date saved ($DUE_DATE)"; else fail "expiry date saved" "got '$saved_d'"; fi
    if [ "$saved_n" = "3" ]; then pass "notify threshold saved (3 days)"; else fail "notify threshold saved" "got '$saved_n'"; fi
  else fail "pantry item with expiry added" "HTTP $STATUS"; fi
fi
if [ -n "$P_FAR" ]; then add_pantry "$P_FAR" "&expirationDate=$FAR_DATE&notifyDaysBefore=3"; I_FAR="$LAST_ID"; fi
if [ -n "$P_NONE" ]; then add_pantry "$P_NONE" ""; I_NONE="$LAST_ID"; fi
if [ -n "$I_FAR" ] && [ -n "$I_NONE" ]; then pass "pantry items outside threshold / without date added"; else fail "pantry items outside threshold / without date added"; fi

call GET "/api/pantry-items?userId=$USER_ID"
expect_status "list pantry" 200 || true

NOTIF_ID=""
if [ -n "$I_DUE" ]; then
  echo "...waiting up to ${NOTIFY_WAIT}s for the notification (scan interval is STOCKER_NOTIFICATIONS_SCAN_INTERVAL)"
  waited=0
  while [ "$waited" -lt "$NOTIFY_WAIT" ]; do
    call GET /api/notifications
    if [ "$STATUS" = "200" ]; then
      NOTIF_ID="$(jget "next((n['id'] for n in d if n['pantryItemId']=='$I_DUE'),'')")"
      [ -n "$NOTIF_ID" ] && break
    fi
    sleep 5; waited=$((waited + 5))
  done
  if [ -n "$NOTIF_ID" ]; then pass "notified: item inside threshold (after ~${waited}s)"; else fail "notified: item inside threshold" "none after ${NOTIFY_WAIT}s; check logs.sh notifications"; fi
fi
call GET /api/notifications
if [ "$STATUS" = "200" ]; then
  extra="$(jget "sum(1 for n in d if n['pantryItemId'] in ('$I_FAR','$I_NONE'))")"
  if [ "$extra" = "0" ]; then pass "no notification for item outside threshold / without date"; else fail "no notification for item outside threshold / without date" "$extra found"; fi
  if [ -n "$NOTIF_ID" ]; then
    n_due="$(jget "sum(1 for n in d if n['pantryItemId']=='$I_DUE')")"
    if [ "$n_due" = "1" ]; then pass "no duplicate notification (1 for the item)"; else fail "no duplicate notification" "$n_due found"; fi
  fi
fi
if [ -n "$NOTIF_ID" ]; then
  call POST "/api/notifications/$NOTIF_ID/acknowledge"
  if expect_status "acknowledge notification" 200; then
    st="$(jget 'd["status"]')"; if [ "$st" = "ACKNOWLEDGED" ]; then pass "status is ACKNOWLEDGED"; else fail "status is ACKNOWLEDGED" "got '$st'"; fi
  fi
fi

# --- 3. favorite stores ---------------------------------------------------------------------
call POST /api/shopping/favorite-stores '{"chainId":"paknsave","storeId":null}'
if expect_status "favorite store save (paknsave)" 201; then
  F1="$(jget 'd["id"]')"; [ -n "$F1" ] && FAV_IDS+=("$F1")
  call POST /api/shopping/favorite-stores '{"chainId":"paknsave","storeId":null}'
  F2="$(jget 'd["id"]')"
  if [ -n "$F1" ] && [ "$F1" = "$F2" ]; then pass "duplicate favorite returns the existing one"; else fail "duplicate favorite returns the existing one" "ids differ"; fi
  call GET /api/shopping/favorite-stores
  cnt="$(jget "sum(1 for f in d if f['chainId']=='paknsave')")"
  if [ "$cnt" = "1" ]; then pass "favorites list shows it once"; else fail "favorites list shows it once" "count $cnt"; fi
  if [ -n "$F1" ]; then
    call DELETE "/api/shopping/favorite-stores/$F1"
    if expect_status "favorite store remove" 204; then
      FAV_IDS=()
      call GET /api/shopping/favorite-stores
      cnt="$(jget "sum(1 for f in d if f['chainId']=='paknsave')")"
      if [ "$cnt" = "0" ]; then pass "favorites list empty after remove"; else fail "favorites list empty after remove" "count $cnt"; fi
    fi
  fi
fi

# --- 4. store products (database only) ------------------------------------------------------
HAVE_DATA=0
for chain in newworld paknsave woolworths; do
  call GET "/api/pricing/stores/$chain/products?limit=5"
  if expect_status "store products: $chain" 200; then
    n="$(jget 'len(d["products"])')"
    echo "      $chain: ${n:-0} product(s) in the sample (limit 5)"
    [ "${n:-0}" -gt 0 ] && HAVE_DATA=1
  fi
done
call GET "/api/pricing/stores/nosuchchain/products"
expect_status "store products: unknown chain rejected" 422 || true
if [ "$HAVE_DATA" -eq 0 ]; then echo "      NOTE: no stored prices found; price steps below can only check shapes and errors."; fi

# --- 5. shopping list ------------------------------------------------------------------------
call POST /api/shopping/items '{"name":"milk","region":"auckland","quantity":2}'
if expect_status "shopping: add item" 201; then
  SITEM="$(jget 'd["id"]')"; st="$(jget 'd["comparisonStatus"]')"
  echo "      item $SITEM comparisonStatus=$st (pending is normal until prices arrive)"
  call GET /api/shopping/items
  found="$(jget "sum(1 for i in d if i['id']=='$SITEM')")"
  if [ "$found" = "1" ]; then pass "shopping: item present in list"; else fail "shopping: item present in list"; fi
fi

# --- 6. match and compare ---------------------------------------------------------------------
ITEM_ID="manual-test-$STAMP"
call GET "/api/pricing/match?term=milk&itemId=$ITEM_ID"
if expect_status "pricing match 'milk'" 200; then
  nm="$(jget 'len(d["matches"])')"; echo "      matches: ${nm:-0}, method: $(jget 'd["matchMethod"]'), stale: $(jget 'd["stale"]')"
fi

CMP="{\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":2}],\"region\":\"auckland\"}"
call POST /api/pricing/compare "$CMP"
if expect_status "compare (all methods)" 200; then
  verdict="$(python3 -I - "$BODY" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); t=d["storeTotals"]
if not t: print("EMPTY"); sys.exit()
bad=[s["chainId"] for s in t if abs(s["totalWithFulfilment"]-(s["totalAmount"]+s["fulfilmentCost"]))>0.005]
cheap=min(t,key=lambda s:s["totalWithFulfilment"])["chainId"]
print("BADSUM "+",".join(bad) if bad else ("OKCHEAP" if cheap==d["cheapestChainId"] or any(s["itemsPriced"]==0 for s in t) else "NOTCHEAPEST "+cheap+" vs "+d["cheapestChainId"]))
PY
)"
  case "$verdict" in
    EMPTY) skip "compare: totals and cheapest" "no stored prices for the matched item" ;;
    OKCHEAP) pass "compare: total = items + fulfilment, cheapest recommendation consistent" ;;
    *) fail "compare: totals and cheapest" "$verdict" ;;
  esac
fi

call POST /api/pricing/compare "{\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":2}],\"region\":\"auckland\",\"allowedMethods\":[\"delivery\"]}"
if expect_status "compare (allowedMethods=[delivery])" 200; then
  has_pak="$(jget "sum(1 for s in d['storeTotals'] if s['chainId']=='paknsave')")"
  if [ "$has_pak" = "0" ]; then pass "delivery only: PAK'nSAVE (no delivery) is omitted"; else fail "delivery only: PAK'nSAVE omitted" "still returned"; fi
fi
call POST /api/pricing/compare "{\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":2}],\"region\":\"auckland\",\"allowedMethods\":[\"in_store\"]}"
if expect_status "compare (unknown method) rejected" 422; then
  case "$(jget 'd["error"]')" in *click_and_collect*) pass "unknown method message lists valid methods" ;; *) fail "unknown method message" "$(jget 'd["error"]')" ;; esac
fi
call POST /api/pricing/compare "{\"items\":[{\"itemId\":\"$ITEM_ID\",\"quantity\":2}],\"region\":\"narnia\"}"
if expect_status "compare outside service area" 422; then
  case "$(jget 'd["error"]')" in *"service area"*) pass "outside-service-area message" ;; *) fail "outside-service-area message" "$(jget 'd["error"]')" ;; esac
fi
call POST /api/pricing/compare '{"items":[],"region":"auckland"}'
if expect_status "compare empty list" 422; then
  case "$(jget 'd["error"]')" in *"service area"*) pass "empty list -> service-area message" ;; *) fail "empty list message" "$(jget 'd["error"]')" ;; esac
fi

# --- cleanup -----------------------------------------------------------------------------------
echo "------------------------------------------------------------"
echo "Cleaning up what has a delete API..."
for id in "${FAV_IDS[@]}"; do call DELETE "/api/shopping/favorite-stores/$id"; done
for id in "${PANTRY_IDS[@]}"; do call DELETE "/api/pantry-items/$id"; [[ "$STATUS" =~ ^2 ]] || echo "  could not delete pantry item $id (HTTP $STATUS)"; done
for id in "${PRODUCT_IDS[@]}"; do call DELETE "/api/products/$id"; [[ "$STATUS" =~ ^2 ]] || echo "  could not delete product $id (HTTP $STATUS)"; done
echo "Left behind (no delete API): user $EMAIL, its shopping item(s), its notification(s)."

echo "------------------------------------------------------------"
echo "SUMMARY: $PASSES passed, $FAILS failed, $SKIPS skipped"
if [ "$FAILS" -gt 0 ]; then exit 1; fi
