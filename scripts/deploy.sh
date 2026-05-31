#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

APP_NAME="llmgateway"
ENV="${1:-staging}"
VERSION="${2:-latest}"

echo "=========================================="
echo "Deploying ${APP_NAME} to ${ENV}"
echo "Version: ${VERSION}"
echo "=========================================="

cd "${PROJECT_DIR}"

case "${ENV}" in
    dev)
        echo "Starting development environment..."
        docker compose up -d
        ;;
    staging|production)
        echo "Deploying to ${ENV}..."
        if [ -z "${DOCKER_REGISTRY:-}" ]; then
            echo "Error: DOCKER_REGISTRY environment variable is not set"
            exit 1
        fi

        IMAGE="${DOCKER_REGISTRY}/${APP_NAME}:${VERSION}"
        echo "Pulling image: ${IMAGE}"
        docker pull "${IMAGE}"

        echo "Stopping old containers..."
        docker compose -f "docker-compose.${ENV}.yml" down || true

        echo "Starting new containers..."
        VERSION="${VERSION}" docker compose -f "docker-compose.${ENV}.yml" up -d

        echo "Waiting for service to be healthy..."
        sleep 10

        if docker compose -f "docker-compose.${ENV}.yml" ps | grep -q "healthy"; then
            echo "✅ Deployment successful!"
        else
            echo "❌ Deployment failed - checking logs..."
            docker compose -f "docker-compose.${ENV}.yml" logs
            exit 1
        fi
        ;;
    *)
        echo "Error: Invalid environment '${ENV}'"
        echo "Usage: $0 [dev|staging|production] [version]"
        exit 1
        ;;
esac

echo "=========================================="
echo "Deployment completed successfully!"
echo "=========================================="
