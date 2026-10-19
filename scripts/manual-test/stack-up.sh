#!/usr/bin/env bash
# Preflight, build and start the full stack, then wait for every /healthz. Heavy: builds 8 images.
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

TIMEOUT="${STACK_UP_TIMEOUT:-420}"

"$(dirname "${BASH_SOURCE[0]}")/preflight.sh"
DOCKER_CTX="$(pick_context)"

echo
# One image at a time: building all eight in parallel (each runs its own Gradle build) can exhaust memory and crash
# the machine. Set BUILD_ALL_AT_ONCE=1 to skip this on a machine with plenty of headroom.
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-1}"
if [ "${BUILD_ALL_AT_ONCE:-0}" != "1" ]; then
  for svc in identity catalog inventory pricing shopping notifications analytics gateway; do
    echo "Building image: ${svc} ..."
    dc build "${svc}"
  done
fi
echo "Starting the stack (the first build takes several minutes)..."
dc up -d

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
