#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENVIRONMENT="${1:-dev}"

echo "=========================================="
echo "  Starting ChainETL Platform"
echo "  Environment: ${ENVIRONMENT}"
echo "=========================================="

cd "${PROJECT_DIR}"

export SPRING_PROFILES_ACTIVE="${ENVIRONMENT}"

echo "[1/3] Starting infrastructure services..."
if docker compose version &> /dev/null; then
    docker compose up -d mysql redis
else
    docker-compose up -d mysql redis
fi

echo ""
echo "[2/3] Waiting for services to be ready..."
MAX_RETRIES=30
RETRY_COUNT=0

while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
    if docker exec chainetl-mysql mysqladmin ping -h"localhost" --silent; then
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Waiting for MySQL... (${RETRY_COUNT}/${MAX_RETRIES})"
    sleep 2
done

RETRY_COUNT=0
while [ ${RETRY_COUNT} -lt ${MAX_RETRIES} ]; do
    if docker exec chainetl-redis redis-cli ping > /dev/null 2>&1; then
        break
    fi
    RETRY_COUNT=$((RETRY_COUNT + 1))
    echo "  Waiting for Redis... (${RETRY_COUNT}/${MAX_RETRIES})"
    sleep 2
done

echo ""
echo "[3/3] Starting application..."
echo ""

if [ -f "target/chainetl-platform-*.jar" ]; then
    JAR_FILE=$(ls target/chainetl-platform-*.jar | head -1)
    echo "Running JAR file: ${JAR_FILE}"
    
    java \
        -Xms512m \
        -Xmx1024m \
        -XX:+UseG1GC \
        -Dspring.profiles.active="${ENVIRONMENT}" \
        -jar "${JAR_FILE}"
else
    echo "JAR file not found. Building first..."
    mvn clean package -DskipTests -q
    
    JAR_FILE=$(ls target/chainetl-platform-*.jar | head -1)
    java \
        -Xms512m \
        -Xmx1024m \
        -XX:+UseG1GC \
        -Dspring.profiles.active="${ENVIRONMENT}" \
        -jar "${JAR_FILE}"
fi
