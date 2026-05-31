#!/bin/bash

set -e

HOST="localhost"
PORT="8080"

HEALTH_URL="http://${HOST}:${PORT}/actuator/health"

if ! command -v curl &> /dev/null; then
    echo "curl is not available"
    exit 1
fi

RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" ${HEALTH_URL} || echo "000")

if [ "${RESPONSE}" = "200" ]; then
    HEALTH_STATUS=$(curl -s ${HEALTH_URL} | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    if [ "${HEALTH_STATUS}" = "UP" ]; then
        echo "Health check passed: ${HEALTH_STATUS}"
        exit 0
    else
        echo "Health check failed: status is ${HEALTH_STATUS}"
        exit 1
    fi
else
    echo "Health check failed: HTTP ${RESPONSE}"
    exit 1
fi
