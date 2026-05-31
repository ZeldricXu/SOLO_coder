#!/bin/bash

# Health check script for Infrastructure Platform

set -e

APP_URL="${APP_URL:-http://localhost:8000}"
RETRIES="${RETRIES:-5}"
SLEEP="${SLEEP:-10}"

echo "Checking health of ${APP_URL}..."

for i in $(seq 1 $RETRIES); do
    if curl -f "${APP_URL}/health" > /dev/null 2>&1; then
        echo "✓ Service is healthy!"
        exit 0
    else
        echo "Attempt ${i}/${RETRIES}: Service not healthy yet, waiting ${SLEEP}s..."
        sleep $SLEEP
    fi
done

echo "✗ Service failed health check after ${RETRIES} attempts"
exit 1
