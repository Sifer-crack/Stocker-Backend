#!/usr/bin/env bash
# Pre-flight checks before the manual test. Prints PASS/FAIL per check and never prints a value from .env.
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

FAILS=0
row() { printf '%-6s %-44s %s\n' "$1" "$2" "${3:-}"; if [ "$1" = FAIL ]; then FAILS=$((FAILS + 1)); fi; }

echo "Stocker manual-test preflight"
printf '%-6s %-44s %s\n' RESULT CHECK DETAIL

# 1. docker daemon
DOCKER_CTX=""
if ! command -v docker >/dev/null 2>&1; then
  row FAIL "docker installed" "docker not found in PATH"
elif DOCKER_CTX="$(pick_context)"; then
  row PASS "docker daemon reachable" "context: $DOCKER_CTX"
else
  row FAIL "docker daemon reachable" "no context answers (try: docker context ls)"
fi

# 2. .env present, required variables defined and non-empty (values never printed)
REQUIRED="STOCKER_DB_URL STOCKER_DB_USER STOCKER_DB_PASSWORD STOCKER_NOTIFICATIONS_DB_URL STOCKER_SHOPPING_DB_URL STOCKER_JWT_SECRET"
declare -A VAL=()
if [ -f "$ENV_FILE" ]; then
  row PASS ".env exists in repo root"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    key="${line%%=*}"
    val="${line#*=}"
    val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
    VAL["$key"]="$val"
  done < "$ENV_FILE"
  for v in $REQUIRED; do
    if [ -n "${VAL[$v]:-}" ]; then row PASS "$v defined" "(non-empty)"; else row FAIL "$v defined" "missing or empty in .env"; fi
  done
else
  row FAIL ".env exists in repo root" "copy .env.example to .env and fill it in"
fi

# 3. JWT secret length (>= 32 bytes), without printing it
if [ -n "${VAL[STOCKER_JWT_SECRET]:-}" ]; then
  if [ "${#VAL[STOCKER_JWT_SECRET]}" -ge 32 ]; then row PASS "STOCKER_JWT_SECRET length >= 32" ; else row FAIL "STOCKER_JWT_SECRET length >= 32" "too short for HS256"; fi
fi

# 4. database URLs: jdbc:postgresql://, sslmode, not local
for v in STOCKER_DB_URL STOCKER_NOTIFICATIONS_DB_URL STOCKER_SHOPPING_DB_URL; do
  u="${VAL[$v]:-}"
  [ -n "$u" ] || continue
  case "$u" in jdbc:postgresql://*) row PASS "$v scheme" ;; *) row FAIL "$v scheme" "must start with jdbc:postgresql://" ;; esac
  case "$u" in *sslmode=*) row PASS "$v has sslmode" ;; *) row FAIL "$v has sslmode" "Neon needs ?sslmode=require" ;; esac
  case "$u" in
    *//localhost*|*//127.*|*//0.0.0.0*|*//postgres[:/]*|*//\[::1\]*) row FAIL "$v not local" "points at a local Postgres (Neon only)" ;;
    *) row PASS "$v not local" ;;
  esac
done
# the three URLs must name three different databases
if [ -n "${VAL[STOCKER_DB_URL]:-}" ] && [ -n "${VAL[STOCKER_NOTIFICATIONS_DB_URL]:-}" ] && [ -n "${VAL[STOCKER_SHOPPING_DB_URL]:-}" ]; then
  if [ "${VAL[STOCKER_DB_URL]}" = "${VAL[STOCKER_NOTIFICATIONS_DB_URL]}" ] || [ "${VAL[STOCKER_DB_URL]}" = "${VAL[STOCKER_SHOPPING_DB_URL]}" ] || [ "${VAL[STOCKER_NOTIFICATIONS_DB_URL]}" = "${VAL[STOCKER_SHOPPING_DB_URL]}" ]; then
    row FAIL "three distinct database URLs" "two URLs are identical"
  else
    row PASS "three distinct database URLs"
  fi
fi

# 5. crawl flags: warn (not fail) if on
if [ "${VAL[STOCKER_INGEST_ENABLED]:-false}" = "true" ]; then
  if [ "${VAL[STOCKER_INGEST_DRY_RUN]:-true}" = "false" ]; then MODE="stores rows"; else MODE="dry run, stores nothing"; fi
  row WARN "crawl ON (STOCKER_INGEST_ENABLED)" "live supermarket requests, approved by the owner 2026-10-20; at most 80 pages per chain per day; ${MODE}"
else
  row PASS "crawl OFF (STOCKER_INGEST_ENABLED)"
fi

# 6. compose file valid (stdout discarded so resolved secrets are never shown)
if [ -n "$DOCKER_CTX" ] && [ -f "$ENV_FILE" ]; then
  if dc config >/dev/null 2>&1; then row PASS "docker compose config valid"; else row FAIL "docker compose config valid" "run: docker --context $DOCKER_CTX compose config -q"; fi
  if dc config 2>/dev/null | grep -qE '^  postgres:'; then row FAIL "no local postgres service" "compose still defines one"; else row PASS "no local postgres service"; fi
fi

echo
if [ "$FAILS" -eq 0 ]; then echo "PREFLIGHT: PASS"; else echo "PREFLIGHT: FAIL ($FAILS check(s) failed)"; exit 1; fi
