#!/bin/bash

set -euo pipefail

SERVICE="${1:-chainetl-app}"
LINES="${2:-100}"
FOLLOW="${3:-false}"

echo "=========================================="
echo "  ChainETL Platform Logs"
echo "  Service: ${SERVICE}"
echo "  Lines: ${LINES}"
echo "=========================================="

FOLLOW_FLAG=""
if [ "${FOLLOW}" = "true" ] || [ "${FOLLOW}" = "-f" ]; then
    FOLLOW_FLAG="-f"
fi

if docker compose version &> /dev/null; then
    docker compose logs ${FOLLOW_FLAG} --tail="${LINES}" "${SERVICE}"
else
    docker-compose logs ${FOLLOW_FLAG} --tail="${LINES}" "${SERVICE}"
fi
