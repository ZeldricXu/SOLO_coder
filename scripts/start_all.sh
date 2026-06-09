#!/bin/bash

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "  Inventory Management Platform"
echo "  Starting all services..."
echo "=========================================="

if [ ! -f .env ]; then
    echo "Warning: .env file not found, copying from .env.example"
    cp .env.example .env
fi

echo ""
echo "Starting Redis (for broker and cache)..."
if ! docker ps -q -f name=inventory-redis > /dev/null 2>&1; then
    docker run -d \
        --name inventory-redis \
        -p 6379:6379 \
        --restart unless-stopped \
        redis:7-alpine
    echo "Redis container started"
else
    echo "Redis container already running"
fi

echo ""
echo "Starting PostgreSQL..."
if ! docker ps -q -f name=inventory-postgres > /dev/null 2>&1; then
    docker run -d \
        --name inventory-postgres \
        -p 5432:5432 \
        -e POSTGRES_USER=inventory_user \
        -e POSTGRES_PASSWORD=inventory_password \
        -e POSTGRES_DB=inventory_db \
        -v inventory-postgres-data:/var/lib/postgresql/data \
        --restart unless-stopped \
        postgres:16-alpine
    echo "PostgreSQL container started"
    echo "Waiting for PostgreSQL to be ready..."
    sleep 10
else
    echo "PostgreSQL container already running"
fi

echo ""
echo "Initializing database..."
python scripts/init_db.py

echo ""
echo "Starting Celery Beat (scheduled tasks)..."
if ! pgrep -f "celery -A app.tasks beat" > /dev/null 2>&1; then
    nohup python -m celery -A app.tasks beat \
        --loglevel=INFO \
        --logfile=logs/celery-beat.log > /dev/null 2>&1 &
    echo "Celery Beat started"
else
    echo "Celery Beat already running"
fi

echo ""
echo "Starting Celery Worker (async tasks)..."
if ! pgrep -f "celery -A app.tasks worker" > /dev/null 2>&1; then
    nohup python -m celery -A app.tasks worker \
        --loglevel=INFO \
        --concurrency=4 \
        --logfile=logs/celery-worker.log > /dev/null 2>&1 &
    echo "Celery Worker started"
else
    echo "Celery Worker already running"
fi

echo ""
echo "Starting gRPC Server..."
if ! pgrep -f "python -m app.grpc_api.server" > /dev/null 2>&1; then
    nohup python -m app.grpc_api.server \
        --logfile=logs/grpc-server.log > /dev/null 2>&1 &
    echo "gRPC Server started on port 50051"
else
    echo "gRPC Server already running"
fi

echo ""
echo "Starting FastAPI Web Server..."
if ! pgrep -f "uvicorn app.main:app" > /dev/null 2>&1; then
    nohup python -m uvicorn app.main:app \
        --host 0.0.0.0 \
        --port 8000 \
        --reload \
        --log-level info \
        --log-config logs/uvicorn.log > /dev/null 2>&1 &
    echo "FastAPI Server started on port 8000"
else
    echo "FastAPI Server already running"
fi

echo ""
echo "=========================================="
echo "  All services started successfully!"
echo "=========================================="
echo ""
echo "  Services:"
echo "  - FastAPI API:     http://localhost:8000"
echo "  - Swagger Docs:    http://localhost:8000/docs"
echo "  - Redoc Docs:      http://localhost:8000/redoc"
echo "  - Health Check:    http://localhost:8000/api/v1/health"
echo "  - gRPC Server:     localhost:50051"
echo "  - Redis:           localhost:6379"
echo "  - PostgreSQL:      localhost:5432"
echo ""
echo "  Default Credentials:"
echo "  - Username: admin"
echo "  - Password: Admin@123456"
echo ""
echo "  Logs:"
echo "  - API Server:      logs/uvicorn.log"
echo "  - Celery Worker:   logs/celery-worker.log"
echo "  - Celery Beat:     logs/celery-beat.log"
echo "  - gRPC Server:     logs/grpc-server.log"
echo ""
echo "  To stop all services: ./scripts/stop_all.sh"
echo "=========================================="
