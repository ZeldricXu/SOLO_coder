#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "=========================================="
echo "  Stopping ChainETL Platform"
echo "=========================================="

cd "${PROJECT_DIR}"

echo "[1/2] Stopping application services..."
if docker compose version &> /dev/null; then
    docker compose stop chainetl-app
else
    docker-compose stop chainetl-app
fi

echo ""
echo "[2/2] Stopping infrastructure (optional)..."
read -p "Stop MySQL and Redis? (y/N): " -n 1 -r
echo ""
if [[ ${REPLY} =~ ^[Yy]$ ]]; then
    if docker compose version &> /dev/null; then
        docker compose stop mysql redis
    else
        docker-compose stop mysql redis
    fi
    echo "Infrastructure services stopped"
fi

echo ""
echo "Services stopped successfully"
