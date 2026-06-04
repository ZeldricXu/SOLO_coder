#!/bin/bash
set -e

EDGE_ID="${EDGE_ID:-edge-node-1}"
CDN_CENTER_URL="${CDN_CENTER_URL:-http://cdn-center:8080}"
HEARTBEAT_INTERVAL="${HEARTBEAT_INTERVAL:-10}"

echo "Waiting for cdn-center to be ready..."
until curl -sf "${CDN_CENTER_URL}/health" > /dev/null 2>&1; do
  echo "cdn-center not ready, retrying in 2s..."
  sleep 2
done
echo "cdn-center is ready!"

echo "Registering edge node: ${EDGE_ID}"
curl -sf -X POST "${CDN_CENTER_URL}/api/v1/nodes/register" \
  -H "Content-Type: application/json" \
  -d "{\"node_id\": \"${EDGE_ID}\", \"hostname\": \"${EDGE_ID}\"}"
echo ""
echo "Registration complete."

echo "Starting heartbeat loop for ${EDGE_ID}..."
while true; do
  curl -sf -X POST "${CDN_CENTER_URL}/api/v1/nodes/${EDGE_ID}/heartbeat" \
    -H "Content-Type: application/json" \
    -d "{\"node_id\": \"${EDGE_ID}\", \"status\": \"healthy\"}" && echo "[${EDGE_ID}] Heartbeat sent" || echo "[${EDGE_ID}] Heartbeat failed"
  sleep "${HEARTBEAT_INTERVAL}"
done
