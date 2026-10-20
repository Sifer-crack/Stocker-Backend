#!/usr/bin/env bash
# Stop and remove the stack's containers. Neon data is NOT touched. Add --volumes to also drop the Kafka volume.
set -euo pipefail
# shellcheck source=common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
DOCKER_CTX="$(pick_context)" || { echo "docker daemon not reachable" >&2; exit 1; }
if [ "${1:-}" = "--volumes" ]; then dc down --volumes; else dc down; fi
