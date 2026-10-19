#!/usr/bin/env bash
# Shared helpers for the manual-test scripts (sourced, not run).
# shellcheck disable=SC2034
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${STOCKER_ENV_FILE:-$ROOT/.env}"

# Pick a docker context whose daemon answers. Docker Desktop's context can be a dead socket, so try "default" first.
pick_context() {
  local ctx
  for ctx in ${DOCKER_CONTEXT_OVERRIDE:-} default desktop-linux; do
    if docker --context "$ctx" info >/dev/null 2>&1; then
      echo "$ctx"
      return 0
    fi
  done
  return 1
}

# dc <args>: docker compose against the repo root with the working context and the repo .env.
dc() {
  local files=(-f "$ROOT/docker-compose.yml")
  # Resource caps (8 GiB RAM, 6 cores) unless STACK_LIMITS=off.
  if [ "${STACK_LIMITS:-on}" != "off" ] && [ -f "$ROOT/docker-compose.limits.yml" ]; then
    files+=(-f "$ROOT/docker-compose.limits.yml")
  fi
  docker --context "$DOCKER_CTX" compose --project-directory "$ROOT" --env-file "$ENV_FILE" "${files[@]}" "$@"
}

# Published ports (see docker-compose.yml).
SERVICES="gateway:8080 identity:8081 catalog:8082 shopping:8083 pricing:8084 notifications:8085 analytics:8086 inventory:8087"
