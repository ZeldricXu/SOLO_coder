#!/bin/bash
set -euo pipefail

echo "========================================="
echo "ChaosLab - 生产环境部署脚本"
echo "========================================="

ENVIRONMENT="${1:-production}"
NAMESPACE="chaoslab"
VERSION="${2:-latest}"

echo "环境: $ENVIRONMENT"
echo "版本: $VERSION"
echo "命名空间: $NAMESPACE"

# 检查 kubectl 是否可用
if ! command -v kubectl &> /dev/null; then
    echo "❌ kubectl 未找到，请先安装 kubectl"
    exit 1
fi

# 检查上下文
echo "📋 当前 Kubernetes 上下文:"
kubectl config current-context

# 创建命名空间
echo "🔧 创建命名空间 $NAMESPACE..."
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# 部署配置
echo "📦 部署配置..."
kubectl apply -f k8s/configmap.yaml -n $NAMESPACE

# 部署 RBAC
echo "🔐 部署 RBAC..."
kubectl apply -f k8s/rbac.yaml -n $NAMESPACE

# 部署服务
echo "🚀 部署服务..."
kubectl apply -f k8s/service.yaml -n $NAMESPACE

# 部署 HPA
echo "📊 部署 HPA..."
kubectl apply -f k8s/hpa.yaml -n $NAMESPACE

# 部署 PDB
echo "🛡️  部署 PDB..."
kubectl apply -f k8s/pdb.yaml -n $NAMESPACE

# 部署应用
echo "🏗️  部署应用 (版本: $VERSION)..."
sed "s|{{IMAGE_TAG}}|$VERSION|g" k8s/deployment.yaml | kubectl apply -f - -n $NAMESPACE

# 部署 Ingress
if [ "$ENVIRONMENT" = "production" ]; then
    echo "🌐 部署 Ingress..."
    kubectl apply -f k8s/ingress.yaml -n $NAMESPACE
fi

# 等待部署完成
echo "⏳ 等待部署完成..."
kubectl rollout status deployment/chaoslab -n $NAMESPACE --timeout=300s

# 显示部署状态
echo "✅ 部署完成！"
echo ""
echo "📋 部署状态:"
kubectl get pods -n $NAMESPACE
echo ""
echo "📋 服务状态:"
kubectl get svc -n $NAMESPACE
echo ""
echo "📋 HPA 状态:"
kubectl get hpa -n $NAMESPACE
echo ""
echo "🎉 ChaosLab 部署完成！"
