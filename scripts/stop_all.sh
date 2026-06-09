#!/bin/bash

echo "=========================================="
echo "  Stopping all Inventory Platform services..."
echo "=========================================="

echo ""
echo "Stopping FastAPI Server..."
pkill -f "uvicorn app.main:app" 2>/dev/null || echo "FastAPI Server not running"

echo ""
echo "Stopping gRPC Server..."
pkill -f "python -m app.grpc_api.server" 2>/dev/null || echo "gRPC Server not running"

echo ""
echo "Stopping Celery Worker..."
pkill -f "celery -A app.tasks worker" 2>/dev/null || echo "Celery Worker not running"

echo ""
echo "Stopping Celery Beat..."
pkill -f "celery -A app.tasks beat" 2>/dev/null || echo "Celery Beat not running"

echo ""
echo "Stopping Docker containers..."
if docker ps -q -f name=inventory-postgres > /dev/null 2>&1; then
    docker stop inventory-postgres
    echo "PostgreSQL container stopped"
fi

if docker ps -q -f name=inventory-redis > /dev/null 2>&1; then
    docker stop inventory-redis
    echo "Redis container stopped"
fi

echo ""
echo "=========================================="
echo "  All services stopped successfully!"
echo "=========================================="
