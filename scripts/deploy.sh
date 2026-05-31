#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

APP_NAME="chainetl-platform"
APP_VERSION="${1:-latest}"
ENVIRONMENT="${2:-prod}"
DOCKER_REGISTRY="${DOCKER_REGISTRY:-ghcr.io/chainetl}"

echo "=========================================="
echo "  ChainETL Platform Deploy Script"
echo "  Version: ${APP_VERSION}"
echo "  Environment: ${ENVIRONMENT}"
echo "=========================================="

check_prerequisites() {
    echo "[1/5] Checking prerequisites..."
    
    if ! command -v docker &> /dev/null; then
        echo "ERROR: Docker is not installed"
        exit 1
    fi
    
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        echo "ERROR: Docker Compose is not installed"
        exit 1
    fi
    
    echo "✓ Prerequisites checked"
}

pull_latest_image() {
    echo "[2/5] Pulling latest Docker image..."
    
    docker pull "${DOCKER_REGISTRY}/${APP_NAME}:${APP_VERSION}"
    
    echo "✓ Image pulled"
}

backup_database() {
    echo "[3/5] Creating database backup..."
    
    BACKUP_DIR="${PROJECT_DIR}/backups"
    BACKUP_FILE="${BACKUP_DIR}/db-backup-$(date +%Y%m%d_%H%M%S).sql"
    
    mkdir -p "${BACKUP_DIR}"
    
    if command -v mysqldump &> /dev/null; then
        mysqldump -h"${DB_HOST:-localhost}" -u"${DB_USERNAME:-root}" -p"${DB_PASSWORD:-password}" "${DB_NAME:-chainetl}" > "${BACKUP_FILE}"
        gzip "${BACKUP_FILE}"
        echo "✓ Database backup created: ${BACKUP_FILE}.gz"
    else
        echo "⚠ mysqldump not found, skipping database backup"
    fi
}

deploy_application() {
    echo "[4/5] Deploying application..."
    
    cd "${PROJECT_DIR}"
    
    export APP_VERSION="${APP_VERSION}"
    export SPRING_PROFILES_ACTIVE="${ENVIRONMENT}"
    
    if docker compose version &> /dev/null; then
        docker compose up -d --no-deps --build chainetl-app
    else
        docker-compose up -d --no-deps --build chainetl-app
    fi
    
    echo "✓ Application deployed"
}

health_check() {
    echo "[5/5] Performing health check..."
    
    MAX_RETRIES=30
    RETRY_DELAY=5
    RETRY_COUNT=0
    
    while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
        if curl -s -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
            HEALTH_STATUS=$(curl -s http://localhost:8080/actuator/health | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
            
            if [ "${HEALTH_STATUS}" = "UP" ]; then
                echo "✓ Health check passed: ${HEALTH_STATUS}"
                echo ""
                echo "=========================================="
                echo "  Deployment Successful!"
                echo "  Application is running on port 8080"
                echo "=========================================="
                return 0
            fi
        fi
        
        RETRY_COUNT=$((RETRY_COUNT + 1))
        echo "  Waiting for application to start... (${RETRY_COUNT}/${MAX_RETRIES})"
        sleep ${RETRY_DELAY}
    done
    
    echo "ERROR: Health check failed after ${MAX_RETRIES} attempts"
    exit 1
}

rollback() {
    echo ""
    echo "Rolling back deployment..."
    cd "${PROJECT_DIR}"
    
    if docker compose version &> /dev/null; then
        docker compose logs --tail=100 chainetl-app
    else
        docker-compose logs --tail=100 chainetl-app
    fi
    
    exit 1
}

trap rollback ERR

check_prerequisites
pull_latest_image
backup_database
deploy_application
health_check

exit 0
