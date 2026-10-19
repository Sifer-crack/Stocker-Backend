#!/usr/bin/env bash
# Preflight, build and start the full stack, then wait for every /healthz. Heavy: builds 8 images.
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TIMEOUT="${STACK_UP_TIMEOUT:-420}"

"$(dirname "${BASH_SOURCE[0]}")/preflight.sh"
DOCKER_CTX="$(pick_context)"

echo
echo "Starting the stack (first run builds every image; this takes several minutes and a lot of memory)..."
dc up -d --build

echo
echo "Waiting up to ${TIMEOUT}s for each /healthz..."
declare -A STATE=()
start=$SECONDS
for s in $SERVICES; do STATE["${s%%:*}"]="waiting"; done
while :; do
  pending=0
  for s in $SERVICES; do
    name="${s%%:*}"; port="${s##*:}"
    if [ "${STATE[$name]}" != "UP" ]; then
      if curl -fsS -m 3 "http://localhost:$port/healthz" >/dev/null 2>&1; then STATE["$name"]="UP"; else pending=$((pending + 1)); fi
    fi
  done
  [ "$pending" -eq 0 ] && break
  if [ $((SECONDS - start)) -ge "$TIMEOUT" ]; then break; fi
  sleep 5
done

echo
printf '%-14s %-6s %s\n' SERVICE STATE URL
rc=0
for s in $SERVICES; do
  name="${s%%:*}"; port="${s##*:}"
  st="${STATE[$name]}"; [ "$st" = UP ] || { st="DOWN"; rc=1; }
  printf '%-14s %-6s %s\n' "$name" "$st" "http://localhost:$port/healthz"
done
echo
echo "Gateway (use this for everything): http://localhost:8080"
echo "Frontend dev server (separate repo): http://localhost:5173  (VITE_API_BASE_URL=http://localhost:8080)"
if [ "$rc" -ne 0 ]; then echo "Some services are not healthy: see scripts/manual-test/logs.sh <service>"; fi
exit "$rc"
