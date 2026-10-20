#!/usr/bin/env bash
# Usage: logs.sh <service> [extra docker compose logs args, e.g. -f --tail 200]
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
if [ $# -lt 1 ]; then
  echo "usage: $0 <service> [args]   services: gateway identity catalog shopping pricing notifications analytics inventory kafka kafka-init" >&2
  exit 2
fi
DOCKER_CTX="$(pick_context)" || { echo "docker daemon not reachable" >&2; exit 1; }
svc="$1"; shift
if [ $# -eq 0 ]; then set -- --tail 200; fi
dc logs "$@" "$svc"
