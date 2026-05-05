#!/bin/bash

echo "=========================================="
echo "  GameStats 游戏数据分析系统"
echo "  停止脚本"
echo "=========================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo ""
echo "正在停止所有服务..."
docker-compose down

echo ""
echo "=========================================="
echo "  所有服务已停止"
echo "=========================================="
echo ""
echo "提示：如果需要保留数据，请不要执行以下命令"
echo "  docker-compose down -v  (删除所有数据卷)"
echo ""
