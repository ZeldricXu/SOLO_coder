#!/bin/bash
set -euo pipefail

SERVICE_URL="${1:-http://localhost:8080}"
MAX_RETRIES="${2:-30}"
SLEEP_INTERVAL="${3:-2}"

echo "Checking health of ${SERVICE_URL}..."

for i in $(seq 1 "${MAX_RETRIES}"); do
    if curl -f -s "${SERVICE_URL}/api/v1/health" > /dev/null 2>&1; then
        echo "✅ Service is healthy (attempt ${i}/${MAX_RETRIES})"
        exit 0
    fi
    echo "⏳ Waiting for service... (attempt ${i}/${MAX_RETRIES})"
    sleep "${SLEEP_INTERVAL}"
done

echo "❌ Service health check failed after ${MAX_RETRIES} attempts"
exit 1
