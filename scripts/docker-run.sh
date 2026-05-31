#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:-dev}"

if [ "$PROFILE" = "prod" ]; then
  echo "==> Building production image"
  docker compose build app
  echo "==> Running production container"
  docker compose up -d app
elif [ "$PROFILE" = "dev" ]; then
  echo "==> Building dev image with delve debugger"
  docker compose --profile dev build app-dev
  echo "==> Running dev container (debug port 2345)"
  docker compose --profile dev up -d app-dev
  echo "==> Attach debugger to localhost:2345"
else
  echo "Usage: $0 [dev|prod]"
  exit 1
fi
