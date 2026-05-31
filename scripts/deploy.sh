#!/bin/bash
set -e

ENVIRONMENT=${1:-staging}
VERSION=${2:-latest}

echo "=========================================="
echo "Deploying auth-platform to $ENVIRONMENT"
echo "Version: $VERSION"
echo "=========================================="

case "$ENVIRONMENT" in
  development)
    echo "Deploying to development environment..."
    docker compose -f docker-compose.dev.yml up -d --build
    ;;
  staging)
    echo "Deploying to staging environment..."
    docker compose pull
    docker compose up -d
    docker system prune -f
    ;;
  production)
    echo "Deploying to production environment..."
    docker compose pull
    docker compose up -d --no-deps app
    docker system prune -f
    ;;
  *)
    echo "Error: Unknown environment '$ENVIRONMENT'"
    echo "Usage: $0 [development|staging|production] [version]"
    exit 1
    ;;
esac

echo "Waiting for service to be ready..."
for i in {1..30}; do
  if curl -f http://localhost:3000/health > /dev/null 2>&1; then
    echo "✓ Service is healthy!"
    break
  fi
  echo "Waiting... ($i/30)"
  sleep 2
done

echo "=========================================="
echo "Deployment completed successfully!"
echo "=========================================="
