#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

ENV_FILE=".env"
if [ ! -f "$ENV_FILE" ]; then
    if [ -f ".env.example" ]; then
        echo "⚠️  .env file not found, copying from .env.example"
        cp .env.example .env
    else
        echo "❌ .env file not found and .env.example not available"
        exit 1
    fi
fi

export PYTHONPATH="$SCRIPT_DIR"

if [ -d "venv" ]; then
    echo "✅ Activating virtual environment"
    source venv/bin/activate
else
    echo "⚠️  Virtual environment not found, using system Python"
fi

COMMAND="${1:-api}"

case "$COMMAND" in
    api)
        echo "🚀 Starting FastAPI server..."
        python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
        ;;

    worker)
        echo "🚀 Starting Celery worker..."
        celery -A app.tasks.celery_app worker --loglevel=info -Q high_priority,default,batch --concurrency=4
        ;;

    worker-high)
        echo "🚀 Starting Celery worker (high priority)..."
        celery -A app.tasks.celery_app worker --loglevel=info -Q high_priority --concurrency=2
        ;;

    worker-batch)
        echo "🚀 Starting Celery worker (batch)..."
        celery -A app.tasks.celery_app worker --loglevel=info -Q batch --concurrency=2
        ;;

    beat)
        echo "🚀 Starting Celery beat..."
        celery -A app.tasks.celery_app beat --loglevel=info
        ;;

    flower)
        echo "🚀 Starting Celery flower..."
        celery -A app.tasks.celery_app flower --port=5555
        ;;

    init-db)
        echo "🔧 Initializing database..."
        python scripts/init_db.py
        ;;

    all)
        echo "🚀 Starting all services..."

        echo "Starting Celery worker in background..."
        celery -A app.tasks.celery_app worker --loglevel=info -Q high_priority,default,batch --concurrency=4 &
        WORKER_PID=$!

        echo "Starting FastAPI server in foreground..."
        python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

        echo "Stopping background worker..."
        kill $WORKER_PID 2>/dev/null || true
        ;;

    *)
        echo "Usage: $0 {api|worker|worker-high|worker-batch|beat|flower|init-db|all}"
        exit 1
        ;;
esac
