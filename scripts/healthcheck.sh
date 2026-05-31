#!/bin/bash
set -euo pipefail

echo "========================================="
echo "ChaosLab - 服务健康检查脚本"
echo "========================================="

BASE_URL="${1:-http://localhost:3000}"
TIMEOUT=30
INTERVAL=5

echo "检查服务: $BASE_URL"
echo "超时时间: ${TIMEOUT}s"
echo "检查间隔: ${INTERVAL}s"
echo ""

elapsed=0
while [ $elapsed -lt $TIMEOUT ]; do
    if curl -fsS "$BASE_URL/health" > /dev/null 2>&1; then
        echo "✅ 服务健康检查通过"
        echo ""
        echo "📊 健康详情:"
        curl -s "$BASE_URL/health" | python3 -m json.tool 2>/dev/null || curl -s "$BASE_URL/health"
        exit 0
    fi
    echo "⏳ 等待服务就绪... (${elapsed}s/${TIMEOUT}s)"
    sleep $INTERVAL
    elapsed=$((elapsed + INTERVAL))
done

echo "❌ 服务健康检查超时"
exit 1
