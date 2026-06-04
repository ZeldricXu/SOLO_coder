#!/bin/bash

set -e

echo "Starting Recommendation Engine..."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

mkdir -p ./data/logs
mkdir -p ./data/faiss_index
mkdir -p ./data/iceberg_warehouse/fallback
mkdir -p ./data/triton_models

if [ -f .env ]; then
    echo "Loading .env file"
    export $(cat .env | grep -v '^#' | xargs)
fi

echo "Installing dependencies..."
pip install -q -r requirements.txt

echo "Starting service on ${SERVICE_HOST:-0.0.0.0}:${SERVICE_PORT:-8000}"
exec python main.py
