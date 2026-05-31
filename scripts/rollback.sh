#!/bin/bash
set -euo pipefail

echo "========================================="
echo "ChaosLab - 生产环境部署回滚脚本"
echo "========================================="

NAMESPACE="chaoslab"
REVISION="${1:-}"

echo "命名空间: $NAMESPACE"
if [ -n "$REVISION" ]; then
    echo "回滚到版本: $REVISION"
fi

# 检查当前部署状态
echo "📋 当前部署状态:"
kubectl rollout history deployment/chaoslab -n $NAMESPACE

if [ -z "$REVISION" ]; then
    echo ""
    echo "⏪ 回滚到上一个版本..."
    kubectl rollout undo deployment/chaoslab -n $NAMESPACE
else
    echo ""
    echo "⏪ 回滚到版本 $REVISION..."
    kubectl rollout undo deployment/chaoslab -n $NAMESPACE --to-revision=$REVISION
fi

# 等待回滚完成
echo "⏳ 等待回滚完成..."
kubectl rollout status deployment/chaoslab -n $NAMESPACE --timeout=300s

echo ""
echo "✅ 回滚完成！"
echo ""
echo "📋 回滚后状态:"
kubectl get pods -n $NAMESPACE
echo ""
echo "📋 部署历史:"
kubectl rollout history deployment/chaoslab -n $NAMESPACE
