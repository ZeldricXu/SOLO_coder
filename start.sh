#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

export PYTHONPATH="${SCRIPT_DIR}/src:${PYTHONPATH}"

echo "Starting API Gateway..."
echo "Host: ${GATEWAY_HOST:-0.0.0.0}"
echo "Port: ${GATEWAY_PORT:-8000}"
echo "Workers: ${GATEWAY_WORKERS:-4}"
echo ""

python -m gateway.main
