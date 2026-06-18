#!/bin/bash

set -e

echo "=========================================="
echo "  Biz Monitor - 实时业务监控大盘"
echo "=========================================="

echo ""
echo "📦 启动基础设施 (MySQL, Redis, ClickHouse)..."
docker-compose up -d

echo ""
echo "⏳ 等待 MySQL 就绪..."
until docker exec biz-monitor-mysql mysqladmin ping -h localhost -uroot -ppassword --silent; do
    sleep 2
done

echo "⏳ 等待 Redis 就绪..."
until docker exec biz-monitor-redis redis-cli ping | grep -q PONG; do
    sleep 2
done

echo ""
echo "📚 安装后端依赖..."
cd backend
npm install

echo ""
echo "🔄 运行数据库迁移..."
npx prisma generate
npx prisma db push

echo ""
echo "🚀 启动后端服务 (端口 3000)..."
npm run start:dev &
BACKEND_PID=$!

echo ""
echo "📚 安装前端依赖..."
cd ../frontend
npm install

echo ""
echo "🎨 启动前端服务 (端口 5173)..."
npm run dev &
FRONTEND_PID=$!

echo ""
echo "=========================================="
echo "  ✅ 所有服务启动完成！"
echo ""
echo "  前端: http://localhost:5173"
echo "  后端API: http://localhost:3000"
echo "  WebSocket: ws://localhost:3000"
echo ""
echo "  按 Ctrl+C 停止所有服务"
echo "=========================================="

trap "echo ''; echo '🛑 正在停止服务...'; kill $BACKEND_PID $FRONTEND_PID; docker-compose down; exit" INT

wait
