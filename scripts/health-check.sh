#!/bin/bash

set -euo pipefail

HOST="${1:-localhost}"
PORT="${2:-8080}"

echo "=========================================="
echo "  ChainETL Platform Health Check"
echo "  Host: ${HOST}:${PORT}"
echo "=========================================="

BASE_URL="http://${HOST}:${PORT}"

check_endpoint() {
    local name=$1
    local path=$2
    local expected_status=$3
    
    echo -n "Checking ${name}... "
    
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}${path}" || echo "000")
    
    if [ "${RESPONSE}" = "${expected_status}" ]; then
        echo "✓ OK (HTTP ${RESPONSE})"
        return 0
    else
        echo "✗ FAILED (HTTP ${RESPONSE})"
        return 1
    fi
}

check_health_details() {
    echo ""
    echo "Health Details:"
    HEALTH_JSON=$(curl -s "${BASE_URL}/actuator/health" 2>/dev/null || echo "{}")
    
    if command -v jq &> /dev/null; then
        echo "${HEALTH_JSON}" | jq .
    else
        echo "${HEALTH_JSON}"
    fi
}

check_metrics() {
    echo ""
    echo "Application Metrics:"
    
    METRICS=$(curl -s "${BASE_URL}/actuator/metrics" 2>/dev/null || echo "{}")
    
    if command -v jq &> /dev/null; then
        echo "Available metrics count: $(echo "${METRICS}" | jq -r '.names | length')"
    else
        echo "Metrics endpoint available"
    fi
}

ALL_OK=true

check_endpoint "Health" "/actuator/health" "200" || ALL_OK=false
check_endpoint "Info" "/actuator/info" "200" || ALL_OK=false
check_endpoint "Prometheus Metrics" "/actuator/prometheus" "200" || ALL_OK=false
check_endpoint "API Root" "/api/v1/resources" "200" || ALL_OK=false

check_health_details
check_metrics

echo ""
echo "=========================================="
if ${ALL_OK}; then
    echo "  All health checks PASSED ✓"
    exit 0
else
    echo "  Some health checks FAILED ✗"
    exit 1
fi
