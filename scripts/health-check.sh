#!/bin/bash
set -e

HOST=${1:-localhost}
PORT=${2:-3000}

echo "Checking health of http://$HOST:$PORT..."

response=$(curl -s -o /dev/null -w "%{http_code}" http://$HOST:$PORT/health)

if [ "$response" = "200" ]; then
  echo "✓ Service is healthy (HTTP 200)"
  exit 0
else
  echo "✗ Service is unhealthy (HTTP $response)"
  exit 1
fi
