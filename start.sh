#!/bin/bash

set -e

echo "=========================================="
echo "  GameStats 游戏数据分析系统"
echo "  启动脚本"
echo "=========================================="

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -f .env ]; then
    echo "未找到 .env 文件，正在从 .env.example 创建..."
    cp .env.example .env
    echo "请编辑 .env 文件，修改密码等敏感配置"
fi

echo ""
echo "正在启动基础服务 (MySQL, InfluxDB, Redis, Kafka)..."
docker-compose up -d mysql influxdb redis kafka zookeeper

echo ""
echo "等待数据库初始化完成..."
sleep 30

echo ""
echo "检查 MySQL 健康状态..."
for i in {1..10}; do
    if docker-compose exec -T mysql mysqladmin ping -u gamestats -pgamestats123 --silent 2>/dev/null; then
        echo "MySQL 已就绪"
        break
    fi
    echo "等待 MySQL 就绪... (${i}/10)"
    sleep 5
done

echo ""
echo "检查 InfluxDB 健康状态..."
for i in {1..10}; do
    if curl -s http://localhost:8086/health > /dev/null 2>&1; then
        echo "InfluxDB 已就绪"
        break
    fi
    echo "等待 InfluxDB 就绪... (${i}/10)"
    sleep 5
done

echo ""
echo "正在启动应用服务..."
docker-compose up -d event-access profile-service dashboard flink-jobmanager flink-taskmanager

echo ""
echo "等待服务启动..."
sleep 15

echo ""
echo "=========================================="
echo "  服务启动完成！"
echo "=========================================="
echo ""
echo "访问地址："
echo "  监控仪表板: http://localhost:3000"
echo "  事件接入API: http://localhost:8080"
echo "  画像服务API: http://localhost:8002"
echo "  Flink UI: http://localhost:8081"
echo "  InfluxDB UI: http://localhost:8086"
echo ""
echo "服务状态检查命令："
echo "  docker-compose ps"
echo "  docker-compose logs -f [服务名]"
echo ""
