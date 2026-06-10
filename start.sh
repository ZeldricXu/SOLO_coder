#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ -f .env ]; then
    echo "Loading environment from .env..."
    set -a
    source .env
    set +a
fi

FLASK_ENV=${FLASK_ENV:-production}
HOST=${HOST:-0.0.0.0}
PORT=${PORT:-5000}
WORKERS=${GUNICORN_WORKERS:-4}
THREADS=${GUNICORN_THREADS:-2}
LOG_LEVEL=${LOG_LEVEL:-info}

mkdir -p instance data data/uploads data/exports data/snapshots

init_db() {
    echo "Initializing database..."
    python -c "
from app import create_app, db
app = create_app('$FLASK_ENV')
with app.app_context():
    db.create_all()
    print('Database tables created successfully')
"
    if command -v flask &> /dev/null; then
        flask init-db 2>/dev/null || true
        flask seed-templates 2>/dev/null || true
    fi
}

start_gunicorn() {
    echo "Starting Gunicorn (environment: $FLASK_ENV)..."
    echo "Workers: $WORKERS, Threads: $THREADS, Port: $PORT"

    exec gunicorn \
        --bind ${HOST}:${PORT} \
        --workers ${WORKERS} \
        --threads ${THREADS} \
        --timeout 120 \
        --keep-alive 5 \
        --access-logfile - \
        --error-logfile - \
        --log-level ${LOG_LEVEL} \
        --worker-class gthread \
        "app:create_app('${FLASK_ENV}')"
}

start_dev() {
    echo "Starting Flask development server..."
    export FLASK_APP=run.py
    export FLASK_DEBUG=1
    exec python run.py --host ${HOST} --port ${PORT}
}

start_celery_worker() {
    echo "Starting Celery worker..."
    exec celery -A app.tasks.celery_app worker \
        --loglevel=info \
        --concurrency=${CELERY_CONCURRENCY:-2} \
        --pool=prefork \
        --queues=default,report,snapshot,maintenance \
        --max-tasks-per-child=1000 \
        --time-limit=3600
}

start_celery_beat() {
    echo "Starting Celery beat scheduler..."
    exec celery -A app.tasks.celery_app beat \
        --loglevel=info \
        --schedule=data/celerybeat-schedule
}

case "${1:-web}" in
    web)
        init_db
        start_gunicorn
        ;;
    dev)
        init_db
        start_dev
        ;;
    worker)
        start_celery_worker
        ;;
    beat)
        start_celery_beat
        ;;
    init-db)
        init_db
        ;;
    *)
        echo "Usage: $0 {web|dev|worker|beat|init-db}"
        exit 1
        ;;
esac
